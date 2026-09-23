package com.mykaarma.reminders.notification;

import com.mykaarma.reminders.appointment.Appointment.Channel;
import com.mykaarma.reminders.reminder.ReminderType;
import java.util.UUID;

public record NotificationPayload(Channel channel, String recipient, ReminderType reminderType, UUID appointmentId,
		String body) {
}
