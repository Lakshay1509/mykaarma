package com.mykaarma.reminders.reminder;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
class Dispatcher {

	private final String workerId = UUID.randomUUID().toString();

	private final ReminderRepository reminders;

	Dispatcher(ReminderRepository reminders) {
		this.reminders = reminders;
	}

	// No ShedLock, and none should be added: every worker polls at once and SKIP LOCKED
	// hands each a different batch. A lock here would leave all but one worker idle (§6.2).
	@Scheduled(fixedDelay = 1000)
	void poll() {
		reminders.claimDue(workerId);
	}

	@Scheduled(fixedDelay = 30_000)
	void sweep() {
		reminders.releaseExpiredLeases();
	}

}
