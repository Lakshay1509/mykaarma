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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

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

	private UUID idempotencyKey;

	protected Reminder() {
	}

	public Reminder(Appointment appointment, ReminderType reminderType, Instant now) {
		this.appointment = appointment;
		this.reminderType = reminderType;
		this.dueAt = reminderType.dueAt(appointment.getScheduledAt());
		// Booked inside the lead time: as PENDING it would fire at once, e.g. "your
		// appointment is tomorrow" three hours before it (§8.2).
		this.status = dueAt.isBefore(now) ? Status.SKIPPED_LATE : Status.PENDING;
		// Derived, never random: a resend after a crash must carry the same key as the
		// attempt that may already have delivered, so the provider drops it (§7.3).
		this.idempotencyKey = UUID
			.nameUUIDFromBytes((appointment.getPublicId() + ":" + reminderType).getBytes(StandardCharsets.UTF_8));
	}

	public Status getStatus() {
		return status;
	}

	public UUID getIdempotencyKey() {
		return idempotencyKey;
	}

}
