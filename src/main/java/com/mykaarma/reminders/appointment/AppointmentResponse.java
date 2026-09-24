package com.mykaarma.reminders.appointment;

import com.mykaarma.reminders.appointment.Appointment.Status;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

public record AppointmentResponse(UUID id, int version, Status status, Instant scheduledAt, ZonedDateTime localTime) {

	static AppointmentResponse from(Appointment appointment) {
		return new AppointmentResponse(appointment.getPublicId(), appointment.getVersion(), appointment.getStatus(), appointment.getScheduledAt(),
				appointment.getScheduledAt().atZone(appointment.getLocalTz()));
	}

}
