package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import com.mykaarma.reminders.appointment.Appointment;
import com.mykaarma.reminders.appointment.Appointment.Channel;
import com.mykaarma.reminders.appointment.DealershipRepository;
import com.mykaarma.reminders.reminder.Reminder.Status;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ReminderTest {

	private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

	private final DealershipRepository dealerships;

	private final JdbcTemplate jdbc;

	@Autowired
	ReminderTest(DealershipRepository dealerships, JdbcTemplate jdbc) {
		this.dealerships = dealerships;
		this.jdbc = jdbc;
	}

	@ParameterizedTest(name = "booked {0} out -> T24H {1}, T2H {2}")
	@CsvSource({ "PT25H, PENDING, PENDING", "PT3H, SKIPPED_LATE, PENDING", "PT30M, SKIPPED_LATE, SKIPPED_LATE" })
	void reminderAlreadyDueAtBooking_startsSkippedLate(Duration notice, Status t24h, Status t2h) {
		Appointment appointment = appointmentIn(notice);

		assertThat(new Reminder(appointment, ReminderType.T24H, NOW).getStatus()).isEqualTo(t24h);
		assertThat(new Reminder(appointment, ReminderType.T2H, NOW).getStatus()).isEqualTo(t2h);
	}

	@Test
	void idempotencyKey_isStablePerAppointmentAndDiffersByType() {
		Appointment appointment = appointmentIn(Duration.ofHours(25));

		assertThat(new Reminder(appointment, ReminderType.T24H, NOW).getIdempotencyKey())
			.isEqualTo(new Reminder(appointment, ReminderType.T24H, NOW).getIdempotencyKey())
			.isNotEqualTo(new Reminder(appointment, ReminderType.T2H, NOW).getIdempotencyKey());
	}

	private Appointment appointmentIn(Duration notice) {
		jdbc.update("INSERT INTO dealership (external_id, name, timezone) VALUES ('DLR-R', 'Test Motors', 'America/Chicago')");
		return new Appointment(dealerships.findByExternalId("DLR-R").orElseThrow(), "Ana Marquez", "+14155550137",
				null, Channel.SMS, null, "2019 Civic", "OIL_CHANGE", NOW.plus(notice), "key-1");
	}

}
