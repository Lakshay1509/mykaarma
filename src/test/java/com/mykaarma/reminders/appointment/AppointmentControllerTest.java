package com.mykaarma.reminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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

// Not @Transactional: inside one shared transaction a retry reads the first request's
// entity from Hibernate's cache, so a replay test never sees what Postgres stored.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
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

	@AfterEach
	void cleanUp() {
		jdbc.update("""
				DELETE FROM appointment a USING dealership d
				WHERE a.dealership_id = d.id AND d.external_id IN ('DLR-T', 'DLR-U')""");
		jdbc.update("DELETE FROM dealership WHERE external_id IN ('DLR-T', 'DLR-U')");
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
	void booking_writesBothRemindersAtTheirLeadTimes() {
		post(VALID);

		assertThat(reminderRows()).containsExactly("T24H PENDING 2026-11-30T20:00:00Z", "T2H PENDING 2026-12-01T18:00:00Z");
	}

	@Test
	void remindersEndpoint_showsWhichRemindersWereSkippedAsTooLate() {
		String location = post(VALID.replace("2026-12-01T14:00:00-06:00", "2026-09-21T10:00:00-05:00")).getResponse()
			.getHeader("Location");

		assertThat(mvc.get().uri(location + "/reminders")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				[{"type": "T24H", "status": "SKIPPED_LATE", "dueAt": "2026-09-20T15:00:00Z"},
				 {"type": "T2H", "status": "PENDING", "dueAt": "2026-09-21T13:00:00Z"}]""")
			.extractingPath("$[*].type")
			.asArray()
			.containsExactly("T24H", "T2H");
	}

	@Test
	void failedReminderInsert_leavesNoAppointmentBehind() {
		jdbc.execute("ALTER TABLE reminder ADD CONSTRAINT reject_all CHECK (false) NOT VALID");
		try {
			post(VALID);
		}
		finally {
			jdbc.execute("ALTER TABLE reminder DROP CONSTRAINT reject_all");
		}

		assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment WHERE idempotency_key = 'key-1'", Integer.class))
			.isZero();
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
	void blankIdempotencyKey_isRejected() {
		assertThat(post(VALID, "")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void nulCharacterInText_isRejected() {
		assertThat(post(VALID.replace("Ana Marquez", "Ana\\u0000Marquez"))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void bodyOver8KB_isRejected() {
		String padded = VALID.replace("{\"dealershipId\"", "{\"junk\": \"" + "x".repeat(9000) + "\", \"dealershipId\"");

		assertThat(post(padded)).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
	}

	@Test
	void localTimeSkippedBySpringForward_isRejected() {
		// 2027-03-14 02:00 in Chicago jumps straight to 03:00.
		assertThat(post(VALID.replace("2026-12-01T14:00:00-06:00", "2027-03-14T02:30:00-06:00")))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void offsetTheDealershipIsNotOnThatDay_isRejected() {
		// Chicago is on -05:00 in July, so 14:00-06:00 would be stored as 15:00 local.
		assertThat(post(VALID.replace("2026-12-01T14:00:00-06:00", "2027-07-01T14:00:00-06:00")))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void scheduledAtInThePast_isRejected() {
		assertThat(post(VALID.replace("2026-12-01", "2026-09-20"))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void scheduledAtMoreThan365DaysOut_isRejected() {
		assertThat(post(VALID.replace("2026-12-01", "2027-12-01"))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void retryWithSameKey_returnsTheOriginalAndCreatesNothing() throws Exception {
		MvcTestResult first = post(VALID);
		MvcTestResult retry = post(VALID);

		assertThat(first).hasStatus(HttpStatus.CREATED);
		assertThat(retry).hasStatusOk().bodyJson().isStrictlyEqualTo(first.getResponse().getContentAsString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment WHERE idempotency_key = 'key-1'", Integer.class))
			.isEqualTo(1);
		assertThat(reminderRows()).hasSize(2);
	}

	@Test
	void retryWithSubMicrosecondTime_replaysInsteadOfConflicting() throws Exception {
		String nanos = VALID.replace("14:00:00-06:00", "14:00:00.123456789-06:00");
		MvcTestResult first = post(nanos);
		MvcTestResult retry = post(nanos);

		assertThat(retry).hasStatusOk().bodyJson().isStrictlyEqualTo(first.getResponse().getContentAsString());
	}

	@Test
	void retryArrivingAfterTheAppointmentTime_stillReplaysTheOriginal() {
		// Booked for 11:00Z and "now" is 12:00Z, so a new booking for that time would get a 422.
		jdbc.update("""
				INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel, vehicle_vin,
				    vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				SELECT id, 'Ana Marquez', '+14155550137', 'SMS', '1HGCM82633A004352', '2019 Civic', 'OIL_CHANGE',
				    '2026-09-21T11:00:00Z', 'America/Chicago', 'BOOKED', 'key-1'
				FROM dealership WHERE external_id = 'DLR-T'""");

		assertThat(post(VALID.replace("2026-12-01T14:00:00-06:00", "2026-09-21T06:00:00-05:00"))).hasStatusOk();
	}

	@Test
	void sameKeyWithDifferentDetails_isRejected() {
		post(VALID);

		assertThat(post(VALID.replace("2026-12-01", "2026-12-02"))).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void sameKeyAtAnotherDealership_isANewBooking() {
		jdbc.update("INSERT INTO dealership (external_id, name, timezone) VALUES ('DLR-U', 'Other Motors', 'America/Chicago')");
		post(VALID);

		assertThat(post(VALID.replace("DLR-T", "DLR-U"))).hasStatus(HttpStatus.CREATED);
	}

	@Test
	void validationError_saysWhichFieldAndWhy() {
		assertThat(post(VALID.replace("+14155550137", "4155550137"))).hasStatus(HttpStatus.BAD_REQUEST)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.extractingPath("$.errors")
			.asArray()
			.containsExactly("customer.phone: must be E.164, e.g. +14155550137");
	}

	@Test
	void pastDate_isAProblemWithAReadableDetail() {
		assertThat(post(VALID.replace("2026-12-01", "2026-09-20"))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("scheduledAt must be in the future");
	}

	@Test
	void cancel_stopsUnsentRemindersButLeavesSentOnesAlone() {
		String location = post(VALID).getResponse().getHeader("Location");
		jdbc.update("""
				UPDATE reminder SET status = 'SENT'
				WHERE reminder_type = 'T24H' AND appointment_id = (SELECT id FROM appointment WHERE idempotency_key = 'key-1')""");

		assertThat(mvc.delete().uri(location)).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(mvc.get().uri(location)).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
		assertThat(reminderRows()).containsExactly("T24H SENT 2026-11-30T20:00:00Z", "T2H CANCELLED 2026-12-01T18:00:00Z");
	}

	@Test
	void reschedule_movesTheAppointment_thenRejectsTheStaleVersion() {
		String location = post(VALID).getResponse().getHeader("Location");
		String moved = """
				{"scheduledAt": "2026-12-04T09:30:00-06:00"}""";

		assertThat(patch(location, "0", moved)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"version": 1, "scheduledAt": "2026-12-04T15:30:00Z", "localTime": "2026-12-04T09:30:00-06:00"}""");
		assertThat(patch(location, "0", moved)).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void reschedule_remindsForTheNewTime_andLeavesTheSentOneSent() {
		String location = post(VALID).getResponse().getHeader("Location");
		jdbc.update("""
				UPDATE reminder SET status = 'SENT'
				WHERE reminder_type = 'T24H' AND appointment_id = (SELECT id FROM appointment WHERE idempotency_key = 'key-1')""");

		assertThat(patch(location, "0", """
				{"scheduledAt": "2026-12-04T14:00:00-06:00"}""")).hasStatusOk();

		assertThat(reminderRows()).containsExactly("T24H SENT 2026-11-30T20:00:00Z", "T2H CANCELLED 2026-12-01T18:00:00Z",
				"T24H PENDING 2026-12-03T20:00:00Z", "T2H PENDING 2026-12-04T18:00:00Z");
		assertThat(jdbc.queryForObject("""
				SELECT count(DISTINCT r.idempotency_key) FROM reminder r JOIN appointment a ON a.id = r.appointment_id
				WHERE a.idempotency_key = 'key-1'""", Integer.class)).isEqualTo(4);
	}

	@Test
	void rescheduleToOneHourAway_skipsBothNewReminders_likeAShortNoticeBooking() {
		String location = post(VALID).getResponse().getHeader("Location");

		assertThat(patch(location, "0", """
				{"scheduledAt": "2026-09-21T08:00:00-05:00"}""")).hasStatusOk();

		assertThat(reminderRows()).containsExactly("T24H SKIPPED_LATE 2026-09-20T13:00:00Z",
				"T2H SKIPPED_LATE 2026-09-21T11:00:00Z", "T24H CANCELLED 2026-11-30T20:00:00Z",
				"T2H CANCELLED 2026-12-01T18:00:00Z");
	}

	@Test
	void rescheduleToTheSameTime_remindsNobodyAgain() {
		String location = post(VALID).getResponse().getHeader("Location");

		assertThat(patch(location, "0", VALID)).hasStatusOk().bodyJson().extractingPath("$.version").isEqualTo(0);

		assertThat(reminderRows()).containsExactly("T24H PENDING 2026-11-30T20:00:00Z", "T2H PENDING 2026-12-01T18:00:00Z");
	}

	@Test
	void reschedulingACancelledAppointment_isAConflict() {
		String location = post(VALID).getResponse().getHeader("Location");
		mvc.delete().uri(location).exchange();

		assertThat(patch(location, "1", """
				{"scheduledAt": "2026-12-04T14:00:00-06:00"}""")).hasStatus(HttpStatus.CONFLICT);
		assertThat(reminderRows()).containsOnly("T24H CANCELLED 2026-11-30T20:00:00Z", "T2H CANCELLED 2026-12-01T18:00:00Z");
	}

	private List<String> reminderRows() {
		return jdbc.queryForList("""
				SELECT r.reminder_type || ' ' || r.status || ' ' || to_char(r.due_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
				FROM reminder r JOIN appointment a ON a.id = r.appointment_id
				WHERE a.idempotency_key = 'key-1' ORDER BY r.due_at""", String.class);
	}

	private MvcTestResult post(String body) {
		return post(body, "key-1");
	}

	private MvcTestResult patch(String location, String version, String body) {
		return mvc.patch()
			.uri(location)
			.header("If-Match", version)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
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
