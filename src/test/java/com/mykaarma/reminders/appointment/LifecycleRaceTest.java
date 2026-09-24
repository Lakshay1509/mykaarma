package com.mykaarma.reminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import com.mykaarma.reminders.notification.NotificationPayload;
import com.mykaarma.reminders.notification.NotificationSender;
import com.mykaarma.reminders.notification.SendResult;
import com.mykaarma.reminders.notification.SendResult.Outcome;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// App clock is frozen at 2026-09-21T12:00Z; the database clock is real. A booking at
// NEAR is 25h out for the app (both reminders PENDING) but already due for the claim
// query, so the dispatcher picks it up within a second. FAR is never due.
@Import({ TestcontainersConfiguration.class, LifecycleRaceTest.Config.class })
@SpringBootTest(properties = "app.worker.enabled=true")
@AutoConfigureMockMvc
class LifecycleRaceTest {

	private static final String NEAR = "2026-09-22T08:00:00-05:00";

	private static final String FAR = "2026-12-01T14:00:00-06:00";

	private final MockMvcTester mvc;

	private final JdbcTemplate jdbc;

	@Autowired
	LifecycleRaceTest(MockMvcTester mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void dealership() {
		Config.delivered.clear();
		Config.gate = null;
		jdbc.update("INSERT INTO dealership (external_id, name, timezone) VALUES ('DLR-L', 'Lifecycle Motors', 'America/Chicago')");
	}

	@AfterEach
	void cleanUp() {
		if (Config.gate != null) {
			Config.gate.countDown();
		}
		await(() -> jdbc.queryForObject("SELECT count(*) FROM reminder_attempt WHERE finished_at IS NULL", Integer.class) == 0);
		jdbc.update("DELETE FROM appointment a USING dealership d WHERE a.dealership_id = d.id AND d.external_id = 'DLR-L'");
		jdbc.update("DELETE FROM dealership WHERE external_id = 'DLR-L'");
	}

	@Test
	void cancelWhileTheSendIsInFlight_stillRecordsTheMessageTheCustomerGot() {
		Config.gate = new CountDownLatch(1);
		String location = book("inflight", NEAR);
		await(() -> Config.delivered.size() == 2);

		assertThat(mvc.delete().uri(location)).hasStatus(204);
		Config.gate.countDown();
		awaitQuiet();

		assertThat(sentKeys()).as("SENT rows vs what the provider accepted")
			.containsExactlyInAnyOrderElementsOf(Config.delivered);
	}

	@Test
	void tenReschedulesOnOneVersion_exactlyOneWins_andWritesExactlyOnePair() {
		String location = book("patch-race", FAR);

		List<Integer> codes = race(10, i -> patch(location, "0", "2026-12-0%dT09:30:00-06:00".formatted(2 + i % 7)));

		assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
		assertThat(codes).filteredOn(c -> c != 200).containsOnly(409);
		assertThat(rows()).containsExactlyInAnyOrder("0 T24H CANCELLED", "0 T2H CANCELLED", "1 T24H PENDING",
				"1 T2H PENDING");
	}

	@Test
	void cancelRacingReschedule_neverFails_andACancelledAppointmentHasNothingPending() {
		for (int round = 0; round < 20; round++) {
			String location = book("cr-" + round, FAR);

			List<Integer> codes = race(2, i -> (i == 0) ? mvc.delete().uri(location).exchange().getResponse().getStatus()
					: patch(location, "0", "2026-12-04T09:30:00-06:00"));

			assertThat(codes).as("round %d: [DELETE, PATCH]", round).doesNotContain(500);
			String status = jdbc.queryForObject("SELECT status FROM appointment WHERE idempotency_key = ?", String.class,
					"cr-" + round);
			if ("CANCELLED".equals(status)) {
				assertThat(jdbc.queryForObject("""
						SELECT count(*) FROM reminder r JOIN appointment a ON a.id = r.appointment_id
						 WHERE a.idempotency_key = ? AND r.status IN ('PENDING', 'CLAIMED')""", Integer.class,
						"cr-" + round))
					.as("round %d", round)
					.isZero();
			}
		}
	}

	private String book(String key, String scheduledAt) {
		return mvc.post()
			.uri("/v1/appointments")
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"dealershipId": "DLR-L",
					 "customer": {"name": "Ana Marquez", "phone": "+14155550137", "channel": "SMS"},
					 "vehicle": {"description": "2019 Civic"},
					 "serviceType": "OIL_CHANGE",
					 "scheduledAt": "%s"}""".formatted(scheduledAt))
			.exchange()
			.getResponse()
			.getHeader("Location");
	}

	private int patch(String location, String version, String scheduledAt) {
		return mvc.patch()
			.uri(location)
			.header("If-Match", version)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"scheduledAt\": \"%s\"}".formatted(scheduledAt))
			.exchange()
			.getResponse()
			.getStatus();
	}

	private List<Integer> race(int n, java.util.function.IntFunction<Integer> call) {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures;
		try (var pool = Executors.newFixedThreadPool(n)) {
			futures = IntStream.range(0, n).mapToObj(i -> pool.submit((Callable<Integer>) () -> {
				start.await();
				try {
					return call.apply(i);
				}
				catch (Exception e) {
					// MockMvc rethrows what a real server would answer 500 for.
					Throwable root = e;
					while (root.getCause() != null) {
						root = root.getCause();
					}
					System.err.println("RACE-500 " + root);
					return 500;
				}
			})).toList();
			start.countDown();
		}
		return futures.stream().map(Future::resultNow).toList();
	}

	private List<String> rows() {
		return jdbc.queryForList("""
				SELECT r.appointment_version || ' ' || r.reminder_type || ' ' || r.status FROM reminder r
				  JOIN appointment a ON a.id = r.appointment_id JOIN dealership d ON d.id = a.dealership_id
				 WHERE d.external_id = 'DLR-L'""", String.class);
	}

	private List<UUID> sentKeys() {
		return jdbc.queryForList("""
				SELECT r.idempotency_key FROM reminder r
				  JOIN appointment a ON a.id = r.appointment_id JOIN dealership d ON d.id = a.dealership_id
				 WHERE d.external_id = 'DLR-L' AND r.status = 'SENT'""", UUID.class);
	}

	private void awaitQuiet() {
		await(() -> jdbc.queryForObject("""
				SELECT count(*) FROM reminder WHERE status = 'CLAIMED'
				   OR id IN (SELECT reminder_id FROM reminder_attempt WHERE finished_at IS NULL)""", Integer.class) == 0);
	}

	private static void await(BooleanSupplier condition) {
		for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {

		static final Queue<UUID> delivered = new ConcurrentLinkedQueue<>();

		static volatile CountDownLatch gate;

		@Bean
		@Primary
		NotificationSender recordingSender() {
			return (NotificationPayload payload, UUID idempotencyKey) -> {
				delivered.add(idempotencyKey);
				try {
					CountDownLatch g = gate;
					if (g != null) {
						g.await();
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return new SendResult(Outcome.OK, "ref-" + idempotencyKey, null);
			};
		}

	}

}
