package com.mykaarma.reminders.reminder;

import com.mykaarma.reminders.appointment.Appointment;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.Instant;

@Entity
public class Reminder {

	public enum Status {
		PENDING, CLAIMED, SENT, DEAD, SKIPPED_LATE, CANCELLED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Appointment appointment;

	@Enumerated(EnumType.STRING)
	private ReminderType reminderType;

	private Instant dueAt;

	@Enumerated(EnumType.STRING)
	private Status status;

	protected Reminder() {
	}

	public Reminder(Appointment appointment, ReminderType reminderType, Instant now) {
		this.appointment = appointment;
		this.reminderType = reminderType;
		this.dueAt = reminderType.dueAt(appointment.getScheduledAt());
		// Booked inside the lead time: as PENDING it would fire at once, e.g. "your
		// appointment is tomorrow" three hours before it (§8.2).
		this.status = dueAt.isBefore(now) ? Status.SKIPPED_LATE : Status.PENDING;
	}

	public Status getStatus() {
		return status;
	}

}
