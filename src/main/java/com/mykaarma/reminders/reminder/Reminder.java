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

	private int appointmentVersion;

	private Instant dueAt;

	@Enumerated(EnumType.STRING)
	private Status status;

	private UUID idempotencyKey;

	protected Reminder() {
	}

	public Reminder(Appointment appointment, ReminderType reminderType, Instant now) {
		this.appointment = appointment;
		this.reminderType = reminderType;
		this.appointmentVersion = appointment.getVersion();
		this.dueAt = reminderType.dueAt(appointment.getScheduledAt());
		// Booked or moved inside the lead time: as PENDING it would fire at once, e.g.
		// "your appointment is tomorrow" three hours before it (§8.2).
		this.status = dueAt.isBefore(now) ? Status.SKIPPED_LATE : Status.PENDING;
		// The version is part of the key, or a provider that dedupes would drop a
		// rescheduled reminder as a repeat of the first one.
		this.idempotencyKey = UUID.nameUUIDFromBytes(
				(appointment.getPublicId() + ":" + reminderType + ":" + appointmentVersion).getBytes(StandardCharsets.UTF_8));
	}

	public Long getId() {
		return id;
	}

	public Appointment getAppointment() {
		return appointment;
	}

	public ReminderType getReminderType() {
		return reminderType;
	}

	public Instant getDueAt() {
		return dueAt;
	}

	public Status getStatus() {
		return status;
	}

	public UUID getIdempotencyKey() {
		return idempotencyKey;
	}

}
