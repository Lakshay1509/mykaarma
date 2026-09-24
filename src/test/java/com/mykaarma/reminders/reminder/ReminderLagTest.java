package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// @Transactional so now() is one instant for the inserts and the read, which makes the lag exact.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ReminderLagTest {

	private final MeterRegistry registry;

	private final JdbcTemplate jdbc;

	private long appointmentId;

	@Autowired
	ReminderLagTest(MeterRegistry registry, JdbcTemplate jdbc) {
		this.registry = registry;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void appointment() {
		appointmentId = jdbc.queryForObject("""
				WITH d AS (INSERT INTO dealership (external_id, name, timezone)
				           VALUES ('DLR-L', 'Lag Motors', 'America/Chicago') RETURNING id)
				INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				       vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				SELECT id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				       now() + interval '1 day', 'America/Chicago', 'BOOKED', 'lag-key' FROM d
				RETURNING id""", Long.class);
	}

	@Test
	void lag_isHowLongTheOldestOverduePendingReminderHasWaited() {
		reminder("T24H", 0, "SENT", "now() - interval '1 hour'");
		reminder("T2H", 0, "PENDING", "now() + interval '1 minute'");
		assertThat(lag()).isZero();

		reminder("T24H", 1, "PENDING", "now() - interval '90 seconds'");
		reminder("T2H", 1, "PENDING", "now() - interval '30 seconds'");
		assertThat(lag()).isEqualTo(90);
	}

	private double lag() {
		return registry.get("reminder.lag").gauge().value();
	}

	private void reminder(String type, int version, String status, String dueAt) {
		jdbc.update("""
				INSERT INTO reminder (appointment_id, reminder_type, appointment_version, due_at, status, idempotency_key)
				VALUES (?, ?, ?, %s, ?, gen_random_uuid())""".formatted(dueAt), appointmentId, type, version,
				status);
	}

}
