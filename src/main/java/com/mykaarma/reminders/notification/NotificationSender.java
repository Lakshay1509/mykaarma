package com.mykaarma.reminders.notification;

import java.util.UUID;

public interface NotificationSender {

	// Must return well inside the 60s lease. A call that outlives it is sent again by
	// another worker, and one that never returns stalls this worker's poll loop and
	// sweeper, which share Spring's single scheduler thread (section 7.3).
	SendResult send(NotificationPayload payload, UUID idempotencyKey);

}
