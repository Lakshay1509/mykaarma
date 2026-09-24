package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import com.mykaarma.reminders.notification.NotificationSender;
import com.mykaarma.reminders.notification.SendResult;
import com.mykaarma.reminders.notification.SendResult.Outcome;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DispatcherConcurrencyTest {

	private static final int WORKERS = 10;

	private final ReminderRepository reminders;

	private final TransactionTemplate transactions;

	private final Clock clock;

	private final JdbcTemplate jdbc;

	@Autowired
	DispatcherConcurrencyTest(ReminderRepository reminders, TransactionTemplate transactions, Clock clock,
			JdbcTemplate jdbc) {
		this.reminders = reminders;
		this.transactions = transactions;
		this.clock = clock;
		this.jdbc = jdbc;
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM appointment WHERE idempotency_key LIKE 'race-%'");
		jdbc.update("DELETE FROM dealership WHERE external_id = 'DLR-R'");
	}

	@Test
	void tenWorkersRacingOneReminder_sendExactlyOnce() throws Exception {
		long id = dueReminder();
		List<UUID> sent = new CopyOnWriteArrayList<>();
		NotificationSender vendor = (payload, idempotencyKey) -> {
			sent.add(idempotencyKey);
			return SendResult.ok("race");
		};

		pollAtOnce(Stream.generate(() -> worker(vendor)).limit(WORKERS).toList());

		assertThat(sent).containsExactly(keyOf(id));
		assertThat(statusAndAttempts(id)).isEqualTo("SENT:1");
	}

	@Test
	void workerDyingAfterTheProviderAcceptedTheMessage_isResentUnderTheSameKey_andSettledOnce() {
		long id = dueReminder();
		List<UUID> providerCalls = new CopyOnWriteArrayList<>();
		Dispatcher crashed = worker((payload, idempotencyKey) -> {
			providerCalls.add(idempotencyKey);
			throw new IllegalStateException("worker died before it could settle");
		});
		Dispatcher survivor = worker((payload, idempotencyKey) -> {
			providerCalls.add(idempotencyKey);
			return SendResult.ok("delivered");
		});

		crashed.poll();
		jdbc.update("UPDATE reminder SET lease_expires_at = now() - interval '1 second' WHERE id = ?", id);
		survivor.sweep();
		survivor.poll();

		assertThat(providerCalls).containsExactly(keyOf(id), keyOf(id));
		assertThat(jdbc.queryForObject("""
				SELECT string_agg(attempt_no || ':' || coalesce(outcome, 'never settled'), ',' ORDER BY attempt_no)
				  FROM reminder_attempt WHERE reminder_id = ?""", String.class, id))
			.isEqualTo("1:never settled,2:OK");
		assertThat(statusAndAttempts(id)).isEqualTo("SENT:2");
	}

	@Test
	void randomWorkloadWithInjectedFailures_sendsEveryReminderOnceUnderItsOwnKey() throws Exception {
		jdbc.update("""
				WITH d AS (INSERT INTO dealership (external_id, name, timezone)
				           VALUES ('DLR-R', 'Race Motors', 'America/Chicago') RETURNING id),
				     a AS (INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				           vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				           SELECT d.id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				                  now() + interval '1 day', 'America/Chicago', 'BOOKED', 'race-' || n
				             FROM d, generate_series(1, 10000) n
				           RETURNING id)
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key)
				SELECT a.id, type, now() - interval '1 minute', 'PENDING', gen_random_uuid()
				  FROM a, unnest(ARRAY['T24H', 'T2H']) type""");
		Set<UUID> called = ConcurrentHashMap.newKeySet();
		Map<UUID, Integer> accepted = new ConcurrentHashMap<>();
		NotificationSender flaky = (payload, idempotencyKey) -> {
			called.add(idempotencyKey);
			int roll = ThreadLocalRandom.current().nextInt(100);
			if (roll < 2) {
				throw new IllegalStateException("worker died mid-send");
			}
			if (roll < 12) {
				return new SendResult(Outcome.RETRYABLE, null, "timeout");
			}
			accepted.merge(idempotencyKey, 1, Integer::sum);
			return SendResult.ok("delivered");
		};
		List<Dispatcher> workers = Stream.generate(() -> worker(flaky)).limit(WORKERS).toList();

		for (int round = 0; round < 100 && unfinished() > 0; round++) {
			pollAtOnce(workers);
			jdbc.update("UPDATE reminder SET lease_expires_at = now() - interval '1 second' WHERE status = 'CLAIMED'");
			workers.getFirst().sweep();
			jdbc.update("UPDATE reminder SET due_at = now() WHERE status = 'PENDING'");
		}

		assertThat(unfinished()).isZero();
		assertThat(accepted.values()).containsOnly(1);
		assertThat(accepted.keySet())
			.isEqualTo(Set.copyOf(jdbc.queryForList("SELECT idempotency_key FROM reminder WHERE status = 'SENT'", UUID.class)));
		assertThat(Set.copyOf(jdbc.queryForList("SELECT idempotency_key FROM reminder", UUID.class))).containsAll(called);
		List<Long> duplicates = jdbc.queryForList("""
				SELECT appointment_id FROM reminder
				 GROUP BY appointment_id, reminder_type, appointment_version HAVING count(*) > 1""", Long.class);
		assertThat(duplicates).isEmpty();
	}

	private static void pollAtOnce(List<Dispatcher> workers) throws Exception {
		CyclicBarrier startingGun = new CyclicBarrier(workers.size());
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Void>> polls = workers.stream().map(worker -> pool.submit(() -> {
				startingGun.await();
				worker.poll();
				return (Void) null;
			})).toList();
			for (Future<Void> poll : polls) {
				poll.get();
			}
		}
	}

	private int unfinished() {
		return jdbc.queryForObject("SELECT count(*) FROM reminder WHERE status IN ('PENDING', 'CLAIMED')",
				Integer.class);
	}

	private Dispatcher worker(NotificationSender vendor) {
		return new Dispatcher(reminders, vendor, transactions, clock, 1, 1);
	}

	private long dueReminder() {
		return jdbc.queryForObject("""
				WITH d AS (INSERT INTO dealership (external_id, name, timezone)
				           VALUES ('DLR-R', 'Race Motors', 'America/Chicago') RETURNING id),
				     a AS (INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				           vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				           SELECT id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				                  now() + interval '1 day', 'America/Chicago', 'BOOKED', 'race-key' FROM d
				           RETURNING id)
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key)
				SELECT id, 'T24H', now() - interval '1 minute', 'PENDING', gen_random_uuid() FROM a
				RETURNING id""", Long.class);
	}

	private UUID keyOf(long id) {
		return jdbc.queryForObject("SELECT idempotency_key FROM reminder WHERE id = ?", UUID.class, id);
	}

	private String statusAndAttempts(long id) {
		return jdbc.queryForObject("SELECT status || ':' || attempt_count FROM reminder WHERE id = ?", String.class,
				id);
	}

}
