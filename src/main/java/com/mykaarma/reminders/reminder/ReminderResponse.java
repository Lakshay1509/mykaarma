package com.mykaarma.reminders.reminder;

import com.mykaarma.reminders.reminder.Reminder.Status;
import java.time.Instant;
import java.util.UUID;

public record ReminderResponse(ReminderType type, Status status, Instant dueAt, UUID idempotencyKey) {

	public static ReminderResponse from(Reminder reminder) {
		return new ReminderResponse(reminder.getReminderType(), reminder.getStatus(), reminder.getDueAt(),
				reminder.getIdempotencyKey());
	}

}
