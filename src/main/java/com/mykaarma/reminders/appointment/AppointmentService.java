package com.mykaarma.reminders.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppointmentService {

	private static final Duration MAX_LEAD = Duration.ofDays(365);

	private final DealershipRepository dealerships;

	private final AppointmentRepository appointments;

	private final Clock clock;

	public AppointmentService(DealershipRepository dealerships, AppointmentRepository appointments, Clock clock) {
		this.dealerships = dealerships;
		this.appointments = appointments;
		this.clock = clock;
	}

	@Transactional
	public Appointment create(CreateAppointmentRequest request, String idempotencyKey) {
		Instant scheduledAt = request.scheduledAt().toInstant();
		Instant now = clock.instant();
		if (!scheduledAt.isAfter(now)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt must be in the future");
		}
		if (scheduledAt.isAfter(now.plus(MAX_LEAD))) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt must be within 365 days");
		}
		Dealership dealership = dealerships.findByExternalId(request.dealershipId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
					"Unknown dealership: " + request.dealershipId()));
		var customer = request.customer();
		var vehicle = request.vehicle();
		return appointments.save(new Appointment(dealership, customer.name(), customer.phone(), customer.email(),
				customer.channel(), vehicle.vin(), vehicle.description(), request.serviceType(),
				scheduledAt, idempotencyKey));
	}

}
