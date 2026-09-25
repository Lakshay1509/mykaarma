package com.mykaarma.reminders.reminder;

import java.time.Duration;
import java.time.Instant;

public enum ReminderType {

	// Sent any closer to the appointment than minUsefulLead, a reminder does more harm
	// than good. A late T24H arrives around the same time as the T2H, and a late T2H
	// after the customer has left (section 8.3).
	T24H(Duration.ofHours(24), Duration.ofHours(2)), T2H(Duration.ofHours(2), Duration.ofMinutes(15));

	private final Duration lead;

	private final Duration minUsefulLead;

	ReminderType(Duration lead, Duration minUsefulLead) {
		this.lead = lead;
		this.minUsefulLead = minUsefulLead;
	}

	// Elapsed time on an Instant, so a DST change in between can't move it (section 6.1).
	public Instant dueAt(Instant scheduledAt) {
		return scheduledAt.minus(lead);
	}

	public Instant usefulUntil(Instant scheduledAt) {
		return scheduledAt.minus(minUsefulLead);
	}

}
