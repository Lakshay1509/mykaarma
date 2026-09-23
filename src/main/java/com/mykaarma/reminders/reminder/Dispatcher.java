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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

	private final Map<Channel, Semaphore> inFlight;

	Dispatcher(ReminderRepository reminders, NotificationSender sender, TransactionTemplate transactions,
			Clock clock, @Value("${app.sender.max-in-flight.sms}") int maxSms,
			@Value("${app.sender.max-in-flight.email}") int maxEmail) {
		this.reminders = reminders;
		this.sender = sender;
		this.transactions = transactions;
		this.clock = clock;
		if (maxSms < 1 || maxEmail < 1) {
			throw new IllegalArgumentException(
					"app.sender.max-in-flight.* must be at least 1: 0 stalls every send, it doesn't turn a channel off");
		}
		this.inFlight = Map.of(Channel.SMS, new Semaphore(maxSms), Channel.EMAIL, new Semaphore(maxEmail));
	}

	// No ShedLock, and none should be added: every worker polls at once and SKIP LOCKED
	// hands each a different batch. A lock here would leave all but one worker idle (§6.2).
	@Scheduled(fixedDelay = 1000)
	void poll() {
		List<Claimed> batch = transactions
			.execute(tx -> reminders.claimDue(workerId).stream().map(Dispatcher::claimed).toList());
		// All sends start together, so a batch takes as long as its slowest send rather
		// than the sum of 200 of them (§6.4). Waiting for the whole batch also keeps
		// claimed_by a safe fence, because this worker can't re-claim a row while its
		// earlier send of it is still in flight.
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			batch.forEach(claimed -> executor.execute(() -> deliver(claimed)));
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
	private void deliver(Claimed claimed) {
		try {
			// Taken before the attempt row opens, so a send still waiting here isn't recorded
			// as started. Given back as soon as the provider answers, since the settle below
			// doesn't call it.
			Semaphore permits = inFlight.get(claimed.payload().channel());
			Long attempt;
			SendResult result;
			permits.acquireUninterruptibly();
			try {
				// Checked after the wait for a slot, which is long when the provider is slow.
				if (!clock.instant().isBefore(claimed.usefulUntil())) {
					log.warn("Reminder {} skipped: too close to the appointment to help", claimed.id());
					reminders.markSkipped(claimed.id(), workerId);
					return;
				}
				// Opened before the send, so a worker that dies mid-call still leaves a row with
				// no outcome, which the sweeper counts toward the crash cap. Closed after the
				// settle, because the reminder's status is what prevents a second send.
				attempt = reminders.openAttempt(claimed.id(), workerId);
				if (attempt == null) {
					log.warn("Reminder {} not sent: its lease ran out while it waited for a send slot", claimed.id());
					return;
				}
				result = sender.send(claimed.payload(), claimed.idempotencyKey());
			}
			finally {
				permits.release();
			}
			int settled = switch (result.outcome()) {
				case OK -> reminders.markSent(claimed.id(), workerId);
				case RETRYABLE -> reminders.scheduleRetry(claimed.id(), workerId, result.error());
				// An invalid number or an unsubscribed customer fails the same way on every
				// try, so retrying only adds provider calls (§7.5).
				case PERMANENT -> reminders.markDead(claimed.id(), workerId, result.error());
			};
			// Whatever the provider said, a worker that lost the row records ABANDONED, so a
			// send that keeps outliving its lease still counts toward the crash cap.
			reminders.closeAttempt(attempt, (settled == 1) ? result.outcome().name() : "ABANDONED",
					result.providerRef(), result.error());
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
