package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mykaarma.reminders.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ReminderSchemaTest {

	private final JdbcTemplate jdbc;

	@Autowired
	ReminderSchemaTest(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Test
	void secondReminderOfTheSameType_isRejectedByTheDatabase() {
		long appointmentId = jdbc.queryForObject("""
				WITH d AS (INSERT INTO dealership (external_id, name, timezone)
				           VALUES ('DLR-S', 'Schema Motors', 'America/Chicago') RETURNING id)
				INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				       vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				SELECT id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				       '2026-10-01T19:00:00Z', 'America/Chicago', 'BOOKED', 'key-1' FROM d
				RETURNING id""", Long.class);

		insertReminder(appointmentId, "T24H");
		insertReminder(appointmentId, "T2H");

		assertThatThrownBy(() -> insertReminder(appointmentId, "T24H")).isInstanceOf(DuplicateKeyException.class);
	}

	private void insertReminder(long appointmentId, String type) {
		jdbc.update("""
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key)
				VALUES (?, ?, '2026-09-30T19:00:00Z', 'PENDING', gen_random_uuid())""", appointmentId, type);
	}

}
