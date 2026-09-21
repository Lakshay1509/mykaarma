package com.mykaarma.reminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import com.mykaarma.reminders.appointment.Appointment.Channel;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AppointmentRepositoryTest {

	private final DealershipRepository dealerships;

	private final AppointmentRepository appointments;

	private final JdbcTemplate jdbc;

	@Autowired
	AppointmentRepositoryTest(DealershipRepository dealerships, AppointmentRepository appointments,
			JdbcTemplate jdbc) {
		this.dealerships = dealerships;
		this.appointments = appointments;
		this.jdbc = jdbc;
	}

	@Test
	void savedAppointment_storesTheInstantAndSnapshotsDealershipTimezone() {
		jdbc.update("INSERT INTO dealership (external_id, name, timezone) VALUES ('DLR-T', 'Test Motors', 'America/Chicago')");
		Dealership dealership = dealerships.findByExternalId("DLR-T").orElseThrow();

		Appointment saved = appointments.saveAndFlush(new Appointment(dealership, "Ana Marquez", "+14155550137", null,
				Channel.SMS, null, "2019 Civic", "OIL_CHANGE", Instant.parse("2026-10-01T19:00:00Z"), "key-1"));

		// Read with SQL, not JPA: a mapping that shifts the instant by the JVM's zone
		// would round-trip through JPA unnoticed.
		var row = jdbc.queryForMap("""
				SELECT scheduled_at = '2026-10-01T19:00:00Z'::timestamptz AS same_instant, local_tz, status
				FROM appointment WHERE public_id = ?""", saved.getPublicId());
		assertThat(row).containsEntry("same_instant", true)
			.containsEntry("local_tz", "America/Chicago")
			.containsEntry("status", "BOOKED");
	}

}
