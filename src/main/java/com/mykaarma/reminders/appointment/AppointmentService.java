package com.mykaarma.reminders.appointment;

import com.mykaarma.reminders.reminder.Reminder;
import com.mykaarma.reminders.reminder.ReminderRepository;
import com.mykaarma.reminders.reminder.ReminderType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppointmentService {

	private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

	private static final Duration MAX_LEAD = Duration.ofDays(365);

	private final DealershipRepository dealerships;

	private final AppointmentRepository appointments;

	private final ReminderRepository reminders;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public AppointmentService(DealershipRepository dealerships, AppointmentRepository appointments,
			ReminderRepository reminders, TransactionTemplate transactions, Clock clock) {
		this.dealerships = dealerships;
		this.appointments = appointments;
		this.reminders = reminders;
		this.transactions = transactions;
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
		checkBookable(request.scheduledAt(), dealership.getTimezone(), now);
		try {
			Appointment booked = transactions.execute(tx -> {
				Appointment saved = appointments.saveAndFlush(candidate);
				remind(saved, now);
				return saved;
			});
			// After the commit, so a booking that rolled back never shows up. No contact
			// details: PII stays out of aggregated logs.
			log.info("APPOINTMENT created id={} dealership={} at={} channel={}", booked.getPublicId(),
					dealership.getExternalId(), booked.getScheduledAt().atZone(booked.getLocalTz()), booked.getChannel());
			return new Booking(booked, true);
		}
		catch (DataIntegrityViolationException e) {
			// A concurrent retry of this booking inserted first. The unique constraint
			// made this insert wait for it, so its row is committed: answer as a replay.
			return replay(appointments.findByDealershipAndIdempotencyKey(dealership, idempotencyKey)
				.orElseThrow(() -> e), candidate);
		}
	}

	public Appointment find(UUID id) {
		return appointments.findByPublicId(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No appointment with id " + id));
	}

	@Transactional
	public void cancel(UUID id) {
		Appointment appointment = find(id);
		appointment.cancel();
		reminders.cancelUnsent(appointment.getId());
	}

	@Transactional
	public Appointment reschedule(UUID id, int version, OffsetDateTime requested) {
		Appointment appointment = find(id);
		if (appointment.getVersion() != version) {
			throw new OptimisticLockingFailureException("Version " + version + " is stale");
		}
		if (appointment.getStatus() != Appointment.Status.BOOKED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Only a booked appointment can be rescheduled; this one is " + appointment.getStatus());
		}
		Instant scheduledAt = requested.toInstant().truncatedTo(ChronoUnit.MICROS);
		if (scheduledAt.equals(appointment.getScheduledAt())) {
			return appointment;
		}
		Instant now = clock.instant();
		checkBookable(requested, appointment.getLocalTz(), now);
		appointment.reschedule(scheduledAt);
		appointments.flush();
		reminders.cancelUnsent(appointment.getId());
		remind(appointment, now);
		return appointment;
	}

	private void remind(Appointment appointment, Instant now) {
		reminders.saveAll(Stream.of(ReminderType.values()).map(type -> new Reminder(appointment, type, now)).toList());
	}

	private static void checkBookable(OffsetDateTime requested, ZoneId zone, Instant now) {
		Instant scheduledAt = requested.toInstant();
		if (!scheduledAt.isAfter(now)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt must be in the future");
		}
		if (scheduledAt.isAfter(now.plus(MAX_LEAD))) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt must be within 365 days");
		}
		// The offset must be one the dealership's timezone uses on that date. This rejects
		// 02:30 on spring-forward night (section 8.1), and a client that sends -06:00 all year
		// for Chicago, which would book summer appointments an hour late.
		if (!zone.getRules().isValidOffset(requested.toLocalDateTime(), requested.getOffset())) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt " + requested
					+ " is not a local time in " + zone + ": wrong offset for that date, or inside a DST gap");
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
