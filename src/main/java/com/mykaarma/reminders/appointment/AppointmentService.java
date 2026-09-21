package com.mykaarma.reminders.appointment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppointmentService {

	private final DealershipRepository dealerships;

	private final AppointmentRepository appointments;

	public AppointmentService(DealershipRepository dealerships, AppointmentRepository appointments) {
		this.dealerships = dealerships;
		this.appointments = appointments;
	}

	@Transactional
	public Appointment create(CreateAppointmentRequest request, String idempotencyKey) {
		Dealership dealership = dealerships.findByExternalId(request.dealershipId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
					"Unknown dealership: " + request.dealershipId()));
		var customer = request.customer();
		var vehicle = request.vehicle();
		return appointments.save(new Appointment(dealership, customer.name(), customer.phone(), customer.email(),
				customer.channel(), vehicle.vin(), vehicle.description(), request.serviceType(),
				request.scheduledAt().toInstant(), idempotencyKey));
	}

}
