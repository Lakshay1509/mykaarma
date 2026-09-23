package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
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

	@Autowired
	DispatcherTest(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM appointment WHERE idempotency_key = 'dispatch-key'");
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

	private String status(long id) {
		return jdbc.queryForObject("SELECT status FROM reminder WHERE id = ?", String.class, id);
	}

}
