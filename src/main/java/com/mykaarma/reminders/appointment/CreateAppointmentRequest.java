package com.mykaarma.reminders.appointment;

import com.mykaarma.reminders.appointment.Appointment.Channel;
import java.time.OffsetDateTime;

// scheduledAt must carry an offset. Without one we would have to guess the zone,
// and a wrong guess sends every reminder an hour or more off.
public record CreateAppointmentRequest(String dealershipId, Customer customer, Vehicle vehicle, String serviceType,
		OffsetDateTime scheduledAt) {

	public record Customer(String name, String phone, String email, Channel channel) {
	}

	public record Vehicle(String vin, String description) {
	}

}
