package com.mykaarma.reminders.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

	// Not @Transactional: after a failed insert Postgres rejects every further
	// statement in that transaction, so the race path has to re-read in a fresh one.
	public Booking create(CreateAppointmentRequest request, String idempotencyKey) {
		Dealership dealership = dealerships.findByExternalId(request.dealershipId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
					"Unknown dealership: " + request.dealershipId()));
		// Postgres stores microseconds. Left at nanoseconds, a retry wouldn't match
		// its own stored booking and would get a 409.
		Instant scheduledAt = request.scheduledAt().toInstant().truncatedTo(ChronoUnit.MICROS);
		var customer = request.customer();
		var vehicle = request.vehicle();
		Appointment candidate = new Appointment(dealership, customer.name(), customer.phone(), customer.email(),
				customer.channel(), vehicle.vin(), vehicle.description(), request.serviceType(), scheduledAt,
				idempotencyKey);

		// Before the time checks, so a retry that arrives after the appointment time
		// has passed still gets its original booking back.
		Optional<Appointment> original = appointments.findByDealershipAndIdempotencyKey(dealership, idempotencyKey);
		if (original.isPresent()) {
			return replay(original.get(), candidate);
		}
		Instant now = clock.instant();
		if (!scheduledAt.isAfter(now)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt must be in the future");
		}
		if (scheduledAt.isAfter(now.plus(MAX_LEAD))) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt must be within 365 days");
		}
		// The offset must be one the dealership's timezone uses on that date. This rejects
		// 02:30 on spring-forward night (§8.1), and a client that sends -06:00 all year
		// for Chicago, which would book summer appointments an hour late.
		ZoneId zone = dealership.getTimezone();
		if (!zone.getRules().isValidOffset(request.scheduledAt().toLocalDateTime(), request.scheduledAt().getOffset())) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt " + request.scheduledAt()
					+ " is not a local time in " + zone + ": wrong offset for that date, or inside a DST gap");
		}
		try {
			return new Booking(appointments.saveAndFlush(candidate), true);
		}
		catch (DataIntegrityViolationException e) {
			// A concurrent retry of this booking inserted first. The unique constraint
			// made this insert wait for it, so its row is committed: answer as a replay.
			return replay(appointments.findByDealershipAndIdempotencyKey(dealership, idempotencyKey)
				.orElseThrow(() -> e), candidate);
		}
	}

	private Booking replay(Appointment original, Appointment retry) {
		if (!original.sameBookingAs(retry)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Idempotency-Key was already used for a different booking");
		}
		return new Booking(original, false);
	}

	public record Booking(Appointment appointment, boolean created) {
	}

}
