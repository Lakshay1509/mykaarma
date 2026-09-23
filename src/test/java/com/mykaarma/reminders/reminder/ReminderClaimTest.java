package com.mykaarma.reminders.reminder;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// Not @Transactional: a second worker only sees rows the first has committed or locked
// on its own connection. Rows are cleaned up by hand instead.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReminderClaimTest {

	private final ReminderRepository reminders;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	private long appointmentId;

	@Autowired
	ReminderClaimTest(ReminderRepository reminders, JdbcTemplate jdbc, TransactionTemplate transactions) {
		this.reminders = reminders;
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	@BeforeEach
	void appointment() {
		appointmentId = jdbc.queryForObject("""
				WITH d AS (INSERT INTO dealership (external_id, name, timezone)
				           VALUES ('DLR-C', 'Claim Motors', 'America/Chicago') RETURNING id)
				INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				       vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				SELECT id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				       now() + interval '1 day', 'America/Chicago', 'BOOKED', 'claim-key' FROM d
				RETURNING id""", Long.class);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM appointment WHERE idempotency_key = 'claim-key'");
		jdbc.update("DELETE FROM dealership WHERE external_id = 'DLR-C'");
	}

	@Test
	void claim_leasesOnlyPendingRemindersThatAreDueByTheDatabaseClock() {
		long due = reminder("T24H", "PENDING", "now() - interval '1 minute'");
		// A minute out, so a claim reading a node's clock a few minutes fast would take it (§9 FM-7).
		reminder("T2H", "PENDING", "now() + interval '1 minute'");

		assertThat(reminders.claimDue("worker-a")).hasSize(1);
		assertThat(jdbc.queryForList("""
				SELECT id FROM reminder
				 WHERE status = 'CLAIMED' AND claimed_by = 'worker-a' AND attempt_count = 1
				   AND lease_expires_at BETWEEN now() + interval '55 seconds' AND now() + interval '60 seconds'""",
				Long.class))
			.containsExactly(due);
		assertThat(reminders.claimDue("worker-b")).isEmpty();
	}

	@Test
	void rowsLockedByOneWorker_areSkippedByAnotherInsteadOfWaitedOn() throws Exception {
		long older = reminder("T24H", "PENDING", "now() - interval '2 minutes'");
		CountDownLatch claimed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> workerA = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
			reminders.claimDue("worker-a");
			claimed.countDown();
			await(release);
		}));
		assertThat(claimed.await(5, SECONDS)).isTrue();
		long newer = reminder("T2H", "PENDING", "now() - interval '1 minute'");

		List<Reminder> claimedByB;
		try {
			// worker-a still holds the older row. Without SKIP LOCKED this call blocks until it commits.
			claimedByB = CompletableFuture.supplyAsync(() -> reminders.claimDue("worker-b")).get(5, SECONDS);
		}
		finally {
			release.countDown();
			workerA.join();
		}

		assertThat(claimedByB).hasSize(1);
		assertThat(jdbc.queryForList("SELECT claimed_by FROM reminder WHERE id IN (?, ?) ORDER BY id", String.class,
				older, newer))
			.containsExactly("worker-a", "worker-b");
	}

	@Test
	void expiredLease_isReleasedForAnotherWorker_whileLiveLeaseIsLeftAlone() {
		long abandoned = reminder("T24H", "PENDING", "now() - interval '2 minutes'");
		long inFlight = reminder("T2H", "PENDING", "now() - interval '1 minute'");
		reminders.claimDue("worker-a");
		jdbc.update("UPDATE reminder SET lease_expires_at = now() - interval '1 second' WHERE id = ?", abandoned);

		reminders.releaseExpiredLeases();

		assertThat(reminders.claimDue("worker-b")).hasSize(1);
		assertThat(jdbc.queryForList("SELECT claimed_by FROM reminder WHERE id IN (?, ?) ORDER BY id", String.class,
				abandoned, inFlight))
			.containsExactly("worker-b", "worker-a");
	}

	@Test
	void leaseExpiringOnTheSixthAttempt_parksTheReminderDead_whileTheFifthIsRequeued() {
		long fifth = reminder("T24H", "PENDING", "now() - interval '2 minutes'");
		long sixth = reminder("T2H", "PENDING", "now() - interval '1 minute'");
		jdbc.update("UPDATE reminder SET attempt_count = CASE WHEN id = ? THEN 4 ELSE 5 END WHERE id IN (?, ?)", fifth,
				fifth, sixth);
		reminders.claimDue("worker-a");
		jdbc.update("UPDATE reminder SET lease_expires_at = now() - interval '1 second' WHERE id IN (?, ?)", fifth,
				sixth);

		assertThat(reminders.releaseExpiredLeases()).containsExactly(sixth);
		assertThat(jdbc.queryForList("SELECT status FROM reminder WHERE id IN (?, ?) ORDER BY id", String.class,
				fifth, sixth))
			.containsExactly("PENDING", "DEAD");
	}

	@Test
	void settleFromAWorkerThatLostItsLease_changesNothing() {
		long id = reminder("T24H", "PENDING", "now() - interval '1 minute'");
		reminders.claimDue("worker-a");
		jdbc.update("UPDATE reminder SET lease_expires_at = now() - interval '1 second' WHERE id = ?", id);
		reminders.releaseExpiredLeases();
		reminders.claimDue("worker-b");

		assertThat(reminders.markSent(id, "worker-a")).isZero();
		assertThat(reminders.markSent(id, "worker-b")).isOne();
		assertThat(reminders.markSent(id, "worker-b")).isZero();
		assertThat(jdbc.queryForObject("SELECT status || ':' || claimed_by FROM reminder WHERE id = ?", String.class,
				id))
			.isEqualTo("SENT:worker-b");
	}

	private long reminder(String type, String status, String dueAt) {
		return jdbc.queryForObject("""
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key)
				VALUES (?, ?, %s, ?, gen_random_uuid()) RETURNING id""".formatted(dueAt), Long.class, appointmentId,
				type, status);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

}
