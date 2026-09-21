package com.mykaarma.reminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AppointmentControllerTest {

	private final MockMvcTester mvc;

	private final JdbcTemplate jdbc;

	@Autowired
	AppointmentControllerTest(MockMvcTester mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void dealership() {
		jdbc.update("INSERT INTO dealership (external_id, name, timezone) VALUES ('DLR-T', 'Test Motors', 'America/Chicago')");
	}

	@Test
	void createdAppointment_isServedAtItsLocation() throws Exception {
		MvcTestResult created = post("DLR-T", "2026-12-01T14:00:00-06:00");

		assertThat(created).hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"status": "BOOKED", "scheduledAt": "2026-12-01T20:00:00Z", "localTime": "2026-12-01T14:00:00-06:00"}""");

		String location = created.getResponse().getHeader("Location");
		assertThat(mvc.get().uri(location)).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo(created.getResponse().getContentAsString());
	}

	@Test
	void unknownDealership_isRejected() {
		assertThat(post("DLR-NOPE", "2026-12-01T14:00:00-06:00")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void scheduledAtWithoutOffset_isRejected() {
		assertThat(post("DLR-T", "2026-12-01T14:00:00")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	private MvcTestResult post(String dealershipId, String scheduledAt) {
		return mvc.post()
			.uri("/v1/appointments")
			.header("Idempotency-Key", "key-1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"dealershipId": "%s",
					 "customer": {"name": "Ana Marquez", "phone": "+14155550137", "channel": "SMS"},
					 "vehicle": {"vin": "1HGCM82633A004352", "description": "2019 Civic"},
					 "serviceType": "OIL_CHANGE",
					 "scheduledAt": "%s"}""".formatted(dealershipId, scheduledAt))
			.exchange();
	}

}
