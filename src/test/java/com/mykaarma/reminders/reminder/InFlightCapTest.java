package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import com.mykaarma.reminders.notification.NotificationPayload;
import com.mykaarma.reminders.notification.NotificationSender;
import com.mykaarma.reminders.notification.SendResult;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({ TestcontainersConfiguration.class, InFlightCapTest.Config.class })
@SpringBootTest(properties = { "app.worker.enabled=true", "app.sender.max-in-flight.sms=2" })
class InFlightCapTest {

	private static final int BATCH = 10;

	private final JdbcTemplate jdbc;

	private final ReminderRepository reminders;

	private final CountingSender vendor;

	@Autowired
	InFlightCapTest(JdbcTemplate jdbc, ReminderRepository reminders, CountingSender vendor) {
		this.jdbc = jdbc;
		this.reminders = reminders;
		this.vendor = vendor;
	}

	@BeforeEach
	void dealership() {
		vendor.entered.clear();
		jdbc.update("INSERT INTO dealership (external_id, name, timezone) VALUES ('DLR-C', 'Cap Motors', 'America/Chicago')");
	}

	@AfterEach
	void cleanUp() {
		vendor.gate.countDown();
		jdbc.update("DELETE FROM appointment WHERE idempotency_key LIKE 'cap-%'");
		jdbc.update("DELETE FROM dealership WHERE external_id = 'DLR-C'");
	}

	@Test
	void batchOfSmsSends_neverHasMoreInFlightThanTheCap() throws InterruptedException {
		dueReminders("batch", BATCH);

		for (int i = 0; i < 50 && sent() < BATCH; i++) {
			Thread.sleep(100);
		}

		assertThat(sent()).isEqualTo(BATCH);
		assertThat(vendor.peak).hasValue(2);
	}

	@Test
	void sendStillWaitingForASlotWhenItsLeaseRunsOut_isLeftToTheWorkerThatReclaimedIt() throws InterruptedException {
		vendor.gate = new CountDownLatch(1);
		dueReminders("queued", 3);
		for (int i = 0; i < 50 && vendor.entered.size() < 2; i++) {
			Thread.sleep(100);
		}
		assertThat(vendor.entered).hasSize(2);
		UUID[] inFlight = vendor.entered.toArray(UUID[]::new);
		long waiting = jdbc.queryForObject("""
				SELECT r.id FROM reminder r JOIN appointment a ON a.id = r.appointment_id
				 WHERE a.idempotency_key LIKE 'cap-queued-%' AND r.idempotency_key NOT IN (?, ?)""", Long.class,
				inFlight[0], inFlight[1]);
		UUID waitingKey = jdbc.queryForObject("SELECT idempotency_key FROM reminder WHERE id = ?", UUID.class, waiting);

		jdbc.update("UPDATE reminder SET lease_expires_at = now() - interval '1 second' WHERE id = ?", waiting);
		reminders.releaseExpiredLeases();
		assertThat(reminders.claimDue("worker-b")).hasSize(1);
		vendor.gate.countDown();

		// Only the next poll can claim this, so once it's SENT the batch above has finished.
		dueReminders("sentinel", 1);
		for (int i = 0; i < 50 && sent() < 3; i++) {
			Thread.sleep(100);
		}

		assertThat(sent()).isEqualTo(3);
		assertThat(vendor.entered).doesNotContain(waitingKey);
		assertThat(jdbc.queryForObject("SELECT status || ':' || claimed_by FROM reminder WHERE id = ?", String.class,
				waiting))
			.isEqualTo("CLAIMED:worker-b");
	}

	@Test
	void sendThatOutlivesItsLease_isRecordedAbandoned_whateverTheProviderAnswered() throws InterruptedException {
		vendor.gate = new CountDownLatch(1);
		dueReminders("late", 1);
		for (int i = 0; i < 50 && vendor.entered.isEmpty(); i++) {
			Thread.sleep(100);
		}
		long id = jdbc.queryForObject("""
				SELECT r.id FROM reminder r JOIN appointment a ON a.id = r.appointment_id
				 WHERE a.idempotency_key = 'cap-late-1'""", Long.class);

		jdbc.update("UPDATE reminder SET lease_expires_at = now() - interval '1 second' WHERE id = ?", id);
		reminders.releaseExpiredLeases();
		assertThat(reminders.claimDue("worker-b")).hasSize(1);
		vendor.gate.countDown();
		for (int i = 0; i < 50 && attempts(id).contains("null"); i++) {
			Thread.sleep(100);
		}

		assertThat(attempts(id)).isEqualTo("ABANDONED:counted");
		assertThat(jdbc.queryForObject("SELECT status || ':' || claimed_by FROM reminder WHERE id = ?", String.class,
				id))
			.isEqualTo("CLAIMED:worker-b");
	}

	private String attempts(long id) {
		return jdbc.queryForObject("""
				SELECT string_agg(coalesce(outcome, 'null') || ':' || coalesce(provider_ref, 'null'), ',')
				  FROM reminder_attempt WHERE reminder_id = ?""", String.class, id);
	}

	private void dueReminders(String prefix, int count) {
		jdbc.update("""
				WITH a AS (INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				           vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				           SELECT d.id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				                  now() + interval '1 day', 'America/Chicago', 'BOOKED', 'cap-' || ? || '-' || n
				             FROM dealership d, generate_series(1, ?) n
				            WHERE d.external_id = 'DLR-C'
				           RETURNING id)
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key)
				SELECT id, 'T24H', now() - interval '1 minute', 'PENDING', gen_random_uuid() FROM a""", prefix, count);
	}

	private int sent() {
		return jdbc.queryForObject("SELECT count(*) FROM reminder WHERE status = 'SENT'", Integer.class);
	}

	static class CountingSender implements NotificationSender {

		final AtomicInteger inFlight = new AtomicInteger();

		final AtomicInteger peak = new AtomicInteger();

		final Set<UUID> entered = ConcurrentHashMap.newKeySet();

		volatile CountDownLatch gate = new CountDownLatch(0);

		@Override
		public SendResult send(NotificationPayload payload, UUID idempotencyKey) {
			entered.add(idempotencyKey);
			peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			try {
				gate.await();
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			finally {
				inFlight.decrementAndGet();
			}
			return SendResult.ok("counted");
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {

		@Bean
		@Primary
		CountingSender countingSender() {
			return new CountingSender();
		}

	}

}
