package com.mykaarma.reminders.reminder;

import java.time.Duration;
import java.time.Instant;

public enum ReminderType {

	T24H(Duration.ofHours(24)), T2H(Duration.ofHours(2));

	private final Duration lead;

	ReminderType(Duration lead) {
		this.lead = lead;
	}

	// Elapsed time on an Instant, so a DST change in between can't move it (§6.1).
	public Instant dueAt(Instant scheduledAt) {
		return scheduledAt.minus(lead);
	}

}
