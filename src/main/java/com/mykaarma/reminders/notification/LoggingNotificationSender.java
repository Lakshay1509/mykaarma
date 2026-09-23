package com.mykaarma.reminders.notification;

import com.mykaarma.reminders.appointment.Appointment.Channel;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

	@Override
	public SendResult send(NotificationPayload payload, UUID idempotencyKey) {
		try {
			Thread.sleep(latency(payload.channel(), ThreadLocalRandom.current()));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted mid-send", ex);
		}
		log.info("NOTIFICATION channel={} to={} type={} appointment={} idempotencyKey={} body={}", payload.channel(),
				mask(payload.recipient()), payload.reminderType(), payload.appointmentId(), idempotencyKey,
				payload.body());
		return SendResult.ok("log-" + idempotencyKey);
	}

	// Measured provider acceptance times (Knock benchmarks, June to September 2026):
	// Twilio p50 114ms, p99 176ms; SES p50 162ms, p99 426ms. Without the delay the stub
	// would hide how long a real send holds a dispatcher thread and uses up its lease.
	// Log-normal through p50 and p99; 2.326 is the z-score of the 99th percentile.
	static Duration latency(Channel channel, RandomGenerator random) {
		double p50 = (channel == Channel.SMS) ? 114 : 162;
		double p99 = (channel == Channel.SMS) ? 176 : 426;
		double sigma = Math.log(p99 / p50) / 2.326;
		return Duration.ofMillis(Math.round(p50 * Math.exp(sigma * random.nextGaussian())));
	}

	static String mask(String recipient) {
		int at = recipient.indexOf('@');
		if (at > 0) {
			return recipient.charAt(0) + "•••" + recipient.substring(at);
		}
		return (recipient.length() < 10) ? "•••"
				: recipient.substring(0, 5) + "•••" + recipient.substring(recipient.length() - 4);
	}

}
