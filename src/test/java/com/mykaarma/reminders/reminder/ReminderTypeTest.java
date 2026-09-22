package com.mykaarma.reminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class ReminderTypeTest {

	private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

	@Test
	void t24hAcrossSpringForward_isTwentyFourElapsedHoursEarlier() {
		Instant scheduledAt = ZonedDateTime.of(2026, 3, 8, 10, 0, 0, 0, CHICAGO).toInstant();

		Instant dueAt = ReminderType.T24H.dueAt(scheduledAt);

		assertThat(dueAt.atZone(CHICAGO).toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 3, 7, 9, 0));
	}

	@Test
	void t2h_isTwoHoursBefore() {
		Instant scheduledAt = Instant.parse("2026-10-01T19:00:00Z");

		assertThat(ReminderType.T2H.dueAt(scheduledAt)).isEqualTo(Instant.parse("2026-10-01T17:00:00Z"));
	}

}
