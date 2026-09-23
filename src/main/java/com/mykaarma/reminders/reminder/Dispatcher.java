package com.mykaarma.reminders.reminder;

import com.mykaarma.reminders.appointment.Appointment;
import com.mykaarma.reminders.appointment.Appointment.Channel;
import com.mykaarma.reminders.notification.NotificationPayload;
import com.mykaarma.reminders.notification.NotificationSender;
import com.mykaarma.reminders.notification.SendResult;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
class Dispatcher {

	private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

	private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US);

	private final String workerId = UUID.randomUUID().toString();

	private final ReminderRepository reminders;

	private final NotificationSender sender;

	private final TransactionTemplate transactions;

	private final Clock clock;

	Dispatcher(ReminderRepository reminders, NotificationSender sender, TransactionTemplate transactions,
			Clock clock) {
		this.reminders = reminders;
		this.sender = sender;
		this.transactions = transactions;
		this.clock = clock;
	}

	// No ShedLock, and none should be added: every worker polls at once and SKIP LOCKED
	// hands each a different batch. A lock here would leave all but one worker idle (§6.2).
	@Scheduled(fixedDelay = 1000)
	void poll() {
		List<Claimed> batch = transactions
			.execute(tx -> reminders.claimDue(workerId).stream().map(Dispatcher::claimed).toList());
		Instant now = clock.instant();
		// All sends start together, so a batch takes as long as its slowest send rather
		// than the sum of 200 of them (§6.4). Waiting for the whole batch also keeps
		// claimed_by a safe fence, because this worker can't re-claim a row while its
		// earlier send of it is still in flight.
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			batch.forEach(claimed -> executor.execute(() -> deliver(claimed, now)));
		}
	}

	@Scheduled(fixedDelay = 30_000)
	void sweep() {
		reminders.releaseExpiredLeases()
			.forEach(id -> log.error("Reminder {} is DEAD: its sends keep dying before they settle", id));
	}

	// Deliberately outside any transaction. A vendor that hangs for 30s would otherwise
	// hold one pooled connection per send, and a single batch would drain the pool the
	// API needs too (§9 FM-10). The lease, not a transaction, protects the row meanwhile.
	private void deliver(Claimed claimed, Instant now) {
		try {
			if (!now.isBefore(claimed.usefulUntil())) {
				log.warn("Reminder {} skipped: too close to the appointment to help", claimed.id());
				reminders.markSkipped(claimed.id(), workerId);
				return;
			}
			SendResult result = sender.send(claimed.payload(), claimed.idempotencyKey());
			int settled = switch (result.outcome()) {
				case OK -> reminders.markSent(claimed.id(), workerId);
				case RETRYABLE -> reminders.scheduleRetry(claimed.id(), workerId, result.error());
				// An invalid number or an unsubscribed customer fails the same way on every
				// try, so retrying only adds provider calls (§7.5).
				case PERMANENT -> reminders.markDead(claimed.id(), workerId, result.error());
			};
			if (settled == 0) {
				log.warn("Reminder {} finished {} after this worker lost its claim on it", claimed.id(),
						result.outcome());
			}
		}
		catch (RuntimeException ex) {
			log.error("Reminder {} was not settled; it is retried once its lease expires", claimed.id(), ex);
		}
	}

	private static Claimed claimed(Reminder reminder) {
		Appointment appointment = reminder.getAppointment();
		String recipient = (appointment.getChannel() == Channel.SMS) ? appointment.getCustomerPhone()
				: appointment.getCustomerEmail();
		// The appointment's snapshotted zone (§4.1), not the server's and not the dealership's
		// current one: a dealership changing its timezone must not move a booked time.
		String when = LOCAL_TIME.format(appointment.getScheduledAt().atZone(appointment.getLocalTz()));
		String body = "Reminder: your %s appointment for the %s is %s.".formatted(appointment.getServiceType(),
				appointment.getVehicleDescription(), when);
		return new Claimed(reminder.getId(), reminder.getIdempotencyKey(),
				reminder.getReminderType().usefulUntil(appointment.getScheduledAt()), new NotificationPayload(
						appointment.getChannel(), recipient, reminder.getReminderType(), appointment.getPublicId(), body));
	}

	private record Claimed(long id, UUID idempotencyKey, Instant usefulUntil, NotificationPayload payload) {
	}

}
