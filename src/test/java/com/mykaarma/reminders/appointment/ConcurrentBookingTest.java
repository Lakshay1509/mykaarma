package com.mykaarma.reminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

// Not @Transactional: the racing requests must commit for real, on separate
// connections, or there is no race. Rows are cleaned up by hand instead.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentBookingTest {

	private static final int RETRIES = 8;

	private final MockMvcTester mvc;

	private final JdbcTemplate jdbc;

	@Autowired
	ConcurrentBookingTest(MockMvcTester mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void dealership() {
		jdbc.update("INSERT INTO dealership (external_id, name, timezone) VALUES ('DLR-RACE', 'Race Motors', 'America/Chicago')");
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM appointment WHERE idempotency_key = 'race-key'");
		jdbc.update("DELETE FROM dealership WHERE external_id = 'DLR-RACE'");
	}

	@Test
	void concurrentRetriesOfOneBooking_createExactlyOneAppointment() {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MvcTestResult>> futures;
		try (var pool = Executors.newFixedThreadPool(RETRIES)) {
			futures = IntStream.range(0, RETRIES).mapToObj(i -> pool.submit(() -> {
				start.await();
				return post();
			})).toList();
			start.countDown();
		}
		List<MvcTestResult> results = futures.stream().map(Future::resultNow).toList();

		assertThat(results).allSatisfy(r -> assertThat(r).hasStatus2xxSuccessful());
		assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 201).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment WHERE idempotency_key = 'race-key'",
				Integer.class))
			.isEqualTo(1);
	}

	private MvcTestResult post() {
		return mvc.post()
			.uri("/v1/appointments")
			.header("Idempotency-Key", "race-key")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"dealershipId": "DLR-RACE",
					 "customer": {"name": "Ana Marquez", "phone": "+14155550137", "channel": "SMS"},
					 "vehicle": {"description": "2019 Civic"},
					 "serviceType": "OIL_CHANGE",
					 "scheduledAt": "2026-12-01T14:00:00-06:00"}""")
			.exchange();
	}

}
