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

	// Each test sends this, or this with exactly one thing broken.
	// "Now" is frozen at 2026-09-21T12:00Z (TestcontainersConfiguration).
	private static final String VALID = """
			{"dealershipId": "DLR-T",
			 "customer": {"name": "Ana Marquez", "phone": "+14155550137", "channel": "SMS"},
			 "vehicle": {"vin": "1HGCM82633A004352", "description": "2019 Civic"},
			 "serviceType": "OIL_CHANGE",
			 "scheduledAt": "2026-12-01T14:00:00-06:00"}""";

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
		MvcTestResult created = post(VALID);

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
		assertThat(post(VALID.replace("DLR-T", "DLR-NOPE"))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void scheduledAtWithoutOffset_isRejected() {
		assertThat(post(VALID.replace("14:00:00-06:00", "14:00:00"))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void missingCustomerName_isRejected() {
		assertThat(post(VALID.replace("\"name\": \"Ana Marquez\", ", ""))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void phoneNotInE164_isRejected() {
		assertThat(post(VALID.replace("+14155550137", "4155550137"))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void emailChannelWithoutEmail_isRejected() {
		assertThat(post(VALID.replace("\"SMS\"", "\"EMAIL\""))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void idempotencyKeyOver128Chars_isRejected() {
		assertThat(post(VALID, "k".repeat(129))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void scheduledAtInThePast_isRejected() {
		assertThat(post(VALID.replace("2026-12-01", "2026-09-20"))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void scheduledAtMoreThan365DaysOut_isRejected() {
		assertThat(post(VALID.replace("2026-12-01", "2027-12-01"))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	private MvcTestResult post(String body) {
		return post(body, "key-1");
	}

	private MvcTestResult post(String body, String idempotencyKey) {
		return mvc.post()
			.uri("/v1/appointments")
			.header("Idempotency-Key", idempotencyKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

}
