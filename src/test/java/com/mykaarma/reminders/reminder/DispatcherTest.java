package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// Not @Transactional: the dispatcher claims on its own thread and connection, so the
// reminder has to be committed before it can see it.
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.worker.enabled=true")
class DispatcherTest {

	private final JdbcTemplate jdbc;

	private final MeterRegistry registry;

	@Autowired
	DispatcherTest(JdbcTemplate jdbc, MeterRegistry registry) {
		this.jdbc = jdbc;
		this.registry = registry;
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM appointment WHERE idempotency_key LIKE 'dispatch-%'");
		jdbc.update("DELETE FROM dealership WHERE external_id = 'DLR-D'");
	}

	@Test
	void dueReminder_isLoggedWithMaskedRecipientAndDealershipLocalTime_andMarkedSent(CapturedOutput output) throws InterruptedException {
		long id = jdbc.queryForObject("""
				WITH d AS (INSERT INTO dealership (external_id, name, timezone)
				           VALUES ('DLR-D', 'Dispatch Motors', 'America/Chicago') RETURNING id),
				     a AS (INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				           vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				           SELECT id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				                  '2026-09-22 19:00:00+00', 'America/Chicago', 'BOOKED', 'dispatch-key' FROM d
				           RETURNING id)
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key)
				SELECT id, 'T24H', now() - interval '1 minute', 'PENDING', gen_random_uuid() FROM a
				RETURNING id""", Long.class);

		for (int i = 0; i < 50 && !"SENT".equals(status(id)); i++) {
			Thread.sleep(100);
		}

		assertThat(status(id)).isEqualTo("SENT");
		assertThat(output).contains("NOTIFICATION channel=SMS to=+1415•••0137")
			.contains("idempotencyKey=" + jdbc.queryForObject("SELECT idempotency_key FROM reminder WHERE id = ?",
					String.class, id))
			.contains("body=Reminder: your OIL_CHANGE appointment for the 2019 Civic is Tue 2:00 PM")
			.doesNotContain("+14155550137");
	}

	// The test clock reads 2026-09-21 12:00Z. The T24H is more overdue than the T2H and
	// still gets sent, so a grace window on due_at can't pass this test (§8.3).
	@Test
	void t2hTwoHoursLate_isSkipped_whileT24hFourHoursLate_stillSends(CapturedOutput output)
			throws InterruptedException {
		jdbc.update("INSERT INTO dealership (external_id, name, timezone) VALUES ('DLR-D', 'Dispatch Motors', 'America/Chicago')");
		long t2h = reminder("T2H", "2026-09-21 12:00:00+00", "2026-09-21 10:00:00+00");
		long t24h = reminder("T24H", "2026-09-22 08:00:00+00", "2026-09-21 08:00:00+00");

		for (int i = 0; i < 50 && jdbc.queryForObject(
				"SELECT count(*) FROM reminder WHERE id IN (?, ?) AND status IN ('PENDING', 'CLAIMED')", Integer.class,
				t2h, t24h) > 0; i++) {
			Thread.sleep(100);
		}

		assertThat(jdbc.queryForList("SELECT status FROM reminder WHERE id IN (?, ?) ORDER BY id", String.class, t2h,
				t24h))
			.containsExactly("SKIPPED_LATE", "SENT");
		assertThat(output).doesNotContain("type=T2H");
		assertThat(registry.get("reminders.skipped.late").counter().count()).isEqualTo(1);
	}

	private long reminder(String type, String scheduledAt, String dueAt) {
		return jdbc.queryForObject("""
				WITH a AS (INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				           vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				           SELECT id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				                  ?::timestamptz, 'America/Chicago', 'BOOKED', 'dispatch-' || ?
				             FROM dealership WHERE external_id = 'DLR-D'
				           RETURNING id)
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key)
				SELECT id, ?, ?::timestamptz, 'PENDING', gen_random_uuid() FROM a
				RETURNING id""", Long.class, scheduledAt, type, type, dueAt);
	}

	private String status(long id) {
		return jdbc.queryForObject("SELECT status FROM reminder WHERE id = ?", String.class, id);
	}

}
