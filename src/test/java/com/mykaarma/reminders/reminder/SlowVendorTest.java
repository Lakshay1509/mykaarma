package com.mykaarma.reminders.reminder;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mykaarma.reminders.TestcontainersConfiguration;
import com.mykaarma.reminders.notification.NotificationPayload;
import com.mykaarma.reminders.notification.NotificationSender;
import com.mykaarma.reminders.notification.SendResult;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

// §9 FM-10: a vendor that hangs must not hold database connections. The batch is twice
// the default pool of 10, so a send inside a transaction would drain the pool.
@Import({ TestcontainersConfiguration.class, SlowVendorTest.Config.class })
@SpringBootTest(properties = "app.worker.enabled=true")
class SlowVendorTest {

	private static final int BATCH = 20;

	private final JdbcTemplate jdbc;

	private final HangingSender vendor;

	@Autowired
	SlowVendorTest(JdbcTemplate jdbc, HangingSender vendor) {
		this.jdbc = jdbc;
		this.vendor = vendor;
	}

	@AfterEach
	void cleanUp() {
		vendor.release.countDown();
		jdbc.update("DELETE FROM appointment WHERE idempotency_key LIKE 'slow-%'");
		jdbc.update("DELETE FROM dealership WHERE external_id = 'DLR-S'");
	}

	@Test
	void hungVendor_holdsNoConnections_whileTheWholeBatchIsInFlight() throws Exception {
		jdbc.update("""
				WITH d AS (INSERT INTO dealership (external_id, name, timezone)
				           VALUES ('DLR-S', 'Slow Motors', 'America/Chicago') RETURNING id),
				     a AS (INSERT INTO appointment (dealership_id, customer_name, customer_phone, channel,
				           vehicle_description, service_type, scheduled_at, local_tz, status, idempotency_key)
				           SELECT d.id, 'Ana Marquez', '+14155550137', 'SMS', '2019 Civic', 'OIL_CHANGE',
				                  now() + interval '1 day', 'America/Chicago', 'BOOKED', 'slow-' || n
				             FROM d, generate_series(1, ?) n
				           RETURNING id)
				INSERT INTO reminder (appointment_id, reminder_type, due_at, status, idempotency_key)
				SELECT a.id, t, now() - interval '1 minute', 'PENDING', gen_random_uuid()
				  FROM a, unnest(ARRAY['T24H', 'T2H']) t""", BATCH / 2);

		assertThat(vendor.inFlight.await(10, SECONDS)).as("all %d sends in flight at once", BATCH).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction'",
				Integer.class))
			.as("transactions held open across the sends")
			.isZero();
		assertThat(CompletableFuture.supplyAsync(() -> jdbc.queryForObject("SELECT 1", Integer.class))
			.get(5, SECONDS)).isEqualTo(1);

		vendor.release.countDown();
		for (int i = 0; i < 50 && sent() < BATCH; i++) {
			Thread.sleep(100);
		}
		assertThat(sent()).isEqualTo(BATCH);
	}

	private int sent() {
		return jdbc.queryForObject("SELECT count(*) FROM reminder WHERE status = 'SENT'", Integer.class);
	}

	static class HangingSender implements NotificationSender {

		final CountDownLatch inFlight = new CountDownLatch(BATCH);

		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public SendResult send(NotificationPayload payload, UUID idempotencyKey) {
			inFlight.countDown();
			try {
				release.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return SendResult.ok("hung");
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {

		@Bean
		@Primary
		HangingSender hangingSender() {
			return new HangingSender();
		}

	}

}
