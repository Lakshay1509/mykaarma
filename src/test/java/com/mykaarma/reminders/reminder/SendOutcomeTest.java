package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import com.mykaarma.reminders.notification.NotificationPayload;
import com.mykaarma.reminders.notification.NotificationSender;
import com.mykaarma.reminders.notification.SendResult;
import com.mykaarma.reminders.notification.SendResult.Outcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({ TestcontainersConfiguration.class, SendOutcomeTest.Config.class })
@SpringBootTest(properties = "app.worker.enabled=true")
class SendOutcomeTest {

	private static final String INVALID_NUMBER = "+15550000000";

	private final JdbcTemplate jdbc;

	@Autowired
	SendOutcomeTest(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM appointment WHERE idempotency_key LIKE 'outcome-%'");
		jdbc.update("DELETE FROM dealership WHERE external_id = 'DLR-O'");
	}

	@Test
	void timeout_returnsReminderToPending_withExponentialBackoff() throws InterruptedException {
		long id = dueReminder("+14155550137", 2);

		awaitSettled(id, 2);

		// Third attempt: 30s * 2^2 = 120s, +/-20% jitter.
		assertThat(jdbc.queryForMap("""
				SELECT status, attempt_count, last_error, claimed_by,
				       due_at BETWEEN now() + interval '90 seconds' AND now() + interval '144 seconds' AS backed_off
				  FROM reminder WHERE id = ?""", id))
			.containsEntry("status", "PENDING")
			.containsEntry("attempt_count", 3)
			.containsEntry("last_error", "timeout")
			.containsEntry("claimed_by", null)
			.containsEntry("backed_off", true);
		assertThat(attempts(id)).containsExactly("3:RETRYABLE:timeout");
	}

	@Test
	void timeoutHoursIntoAnOutage_stillBacksOff_atTheFifteenMinuteCap() throws InterruptedException {
		long id = dueReminder("+14155550137", 45);

		awaitSettled(id, 45);

		assertThat(jdbc.queryForMap("""
				SELECT status, attempt_count,
				       due_at BETWEEN now() + interval '12 minutes' AND now() + interval '18 minutes' AS capped
				  FROM reminder WHERE id = ?""", id))
			.containsEntry("status", "PENDING")
			.containsEntry("attempt_count", 46)
			.containsEntry("capped", true);
	}

	@Test
	void invalidNumber_marksReminderDead_onItsFirstAttempt() throws InterruptedException {
		long id = dueReminder(INVALID_NUMBER, 0);

		awaitSettled(id, 0);

		assertThat(jdbc.queryForMap("SELECT status, attempt_count, last_error FROM reminder WHERE id = ?", id))
			.containsEntry("status", "DEAD")
			.containsEntry("attempt_count", 1)
			.containsEntry("last_error", "invalid number");
		assertThat(attempts(id)).containsExactly("1:PERMANENT:invalid number");
	}

	private long dueReminder(String phone, int attemptsSoFar) {
		return jdbc.queryForObject("""
				WITH d AS (INSERT INTO dealership (external_id, name, timezone)
				           VALUES ('DLR-O', 'Outcome Motors', 'America/Chicago') RETURNING id),
				     a AS (INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				           vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				           SELECT id, 'Ana Marquez', ?, 'SMS', '2019 Civic', 'OIL_CHANGE',
				                  now() + interval '1 day', 'America/Chicago', 'BOOKED', 'outcome-' || ? FROM d
				           RETURNING id)
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key, attempt_count)
				SELECT id, 'T24H', now() - interval '1 minute', 'PENDING', gen_random_uuid(), ? FROM a
				RETURNING id""", Long.class, phone, phone, attemptsSoFar);
	}

	private void awaitSettled(long id, int attemptsSoFar) throws InterruptedException {
		for (int i = 0; i < 50 && !settled(id, attemptsSoFar); i++) {
			Thread.sleep(100);
		}
	}

	// Also waits for the attempt row, which the dispatcher closes just after the settle.
	private boolean settled(long id, int attemptsSoFar) {
		return jdbc.queryForObject("""
				SELECT attempt_count > ? AND status <> 'CLAIMED'
				       AND NOT EXISTS (SELECT 1 FROM reminder_attempt WHERE reminder_id = r.id AND finished_at IS NULL)
				  FROM reminder r WHERE id = ?""", Boolean.class, attemptsSoFar, id);
	}

	private List<String> attempts(long id) {
		return jdbc.queryForList("SELECT attempt_no || ':' || outcome || ':' || error FROM reminder_attempt WHERE reminder_id = ?",
				String.class, id);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {

		@Bean
		@Primary
		NotificationSender scriptedSender() {
			return (NotificationPayload payload, UUID idempotencyKey) -> INVALID_NUMBER.equals(payload.recipient())
					? new SendResult(Outcome.PERMANENT, null, "invalid number")
					: new SendResult(Outcome.RETRYABLE, null, "timeout");
		}

	}

}
