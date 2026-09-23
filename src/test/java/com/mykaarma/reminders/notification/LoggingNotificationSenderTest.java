package com.mykaarma.reminders.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import com.mykaarma.reminders.appointment.Appointment.Channel;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoggingNotificationSenderTest {

	@Test
	void recipients_areMaskedBeforeTheyReachTheLog() {
		assertThat(LoggingNotificationSender.mask("+14155550137")).isEqualTo("+1415•••0137");
		assertThat(LoggingNotificationSender.mask("ana.marquez@example.com")).isEqualTo("a•••@example.com");
		assertThat(LoggingNotificationSender.mask("+4420")).isEqualTo("•••");
	}

	@ParameterizedTest
	@CsvSource({ "SMS, 114, 176", "EMAIL, 162, 426" })
	void simulatedLatency_matchesMeasuredProviderPercentiles(Channel channel, long p50, long p99) {
		Random random = new Random(42);
		long[] millis = LongStream.generate(() -> LoggingNotificationSender.latency(channel, random).toMillis())
			.limit(100_000)
			.sorted()
			.toArray();

		assertThat(millis[50_000]).isCloseTo(p50, withinPercentage(3));
		assertThat(millis[99_000]).isCloseTo(p99, withinPercentage(3));
	}

}
