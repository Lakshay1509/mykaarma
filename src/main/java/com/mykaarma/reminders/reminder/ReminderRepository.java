package com.mykaarma.reminders.reminder;

import com.mykaarma.reminders.appointment.Appointment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

	List<Reminder> findByAppointmentOrderByDueAt(Appointment appointment);

	// Every worker runs this at once (§6.2): SKIP LOCKED hands each one different rows.
	// due_at is compared to the database's now(), never the app's clock (§9 FM-7).
	@Transactional
	@Query(value = """
			UPDATE reminder
			   SET status           = 'CLAIMED',
			       claimed_by       = :workerId,
			       lease_expires_at = now() + interval '60 seconds',
			       attempt_count    = attempt_count + 1
			 WHERE id IN (
			       SELECT id
			         FROM reminder
			        WHERE status = 'PENDING'
			          AND due_at <= now()
			        ORDER BY due_at
			          FOR UPDATE SKIP LOCKED
			        LIMIT 200)
			RETURNING *""", nativeQuery = true)
	List<Reminder> claimDue(String workerId);

	// No ShedLock, because every worker can run this safely: a released row no longer
	// matches, and SKIP LOCKED keeps two sweeps from waiting on each other (§6.3).
	// Counts only sends that died mid-flight: attempts never closed, or closed ABANDONED
	// because the worker lost the row first. Those won't succeed on a seventh try (§9 FM-9).
	// attempt_count would also count RETRYABLE answers, and a reminder that is still useful
	// should keep retrying until the outage ends (FM-5).
	@Transactional
	@Query(value = """
			WITH released AS (
			     UPDATE reminder
			        SET status = CASE WHEN (SELECT count(*) FROM reminder_attempt a
			                                 WHERE a.reminder_id = reminder.id
			                                   AND (a.outcome IS NULL OR a.outcome = 'ABANDONED')) > 5
			                          THEN 'DEAD' ELSE 'PENDING' END,
			            claimed_by = NULL, lease_expires_at = NULL
			      WHERE id IN (
			            SELECT id
			              FROM reminder
			             WHERE status = 'CLAIMED'
			               AND lease_expires_at < now()
			               FOR UPDATE SKIP LOCKED)
			  RETURNING id, status)
			SELECT id FROM released WHERE status = 'DEAD'""", nativeQuery = true)
	List<Long> releaseExpiredLeases();

	// Only the worker still holding the claim may settle it. A worker that ran past its
	// lease has lost the row to the sweeper, and a SENT row never changes again.
	// CANCELLED is allowed because a cancel or reschedule can land while the message is
	// already with the provider. It was delivered, so the row has to say SENT.
	@Transactional
	@Modifying
	@Query(value = """
			UPDATE reminder
			   SET status = 'SENT', sent_at = now(), lease_expires_at = NULL
			 WHERE id = :id
			   AND status IN ('CLAIMED', 'CANCELLED')
			   AND claimed_by = :workerId""", nativeQuery = true)
	int markSent(long id, String workerId);

	// The claim has already counted this attempt, so attempt_count - 1 makes the first
	// retry wait 30s (§7.5). The exponent is capped too: from attempt 40, about nine
	// hours into an outage, 30s · 2ⁿ overflows interval before least() can clamp it.
	@Transactional
	@Modifying
	@Query(value = """
			UPDATE reminder
			   SET status = 'PENDING', claimed_by = NULL, lease_expires_at = NULL, last_error = :error,
			       due_at = now() + least(interval '30 seconds' * power(2, least(attempt_count - 1, 5)), interval '15 minutes')
			                        * (0.8 + random() * 0.4)
			 WHERE id = :id
			   AND status = 'CLAIMED'
			   AND claimed_by = :workerId""", nativeQuery = true)
	int scheduleRetry(long id, String workerId, String error);

	@Transactional
	@Modifying
	@Query(value = """
			UPDATE reminder
			   SET status = 'DEAD', lease_expires_at = NULL, last_error = :error
			 WHERE id = :id
			   AND status = 'CLAIMED'
			   AND claimed_by = :workerId""", nativeQuery = true)
	int markDead(long id, String workerId, String error);

	@Transactional
	@Modifying
	@Query(value = """
			UPDATE reminder
			   SET status = 'CANCELLED', lease_expires_at = NULL
			 WHERE appointment_id = :appointmentId
			   AND status IN ('PENDING', 'CLAIMED')""", nativeQuery = true)
	void cancelUnsent(long appointmentId);

	@Transactional
	@Modifying
	@Query(value = """
			UPDATE reminder
			   SET status = 'SKIPPED_LATE', lease_expires_at = NULL
			 WHERE id = :id
			   AND status = 'CLAIMED'
			   AND claimed_by = :workerId""", nativeQuery = true)
	void markSkipped(long id, String workerId);

	// Also renews the lease, so a send that queued for a slot still gets the full 60s.
	// Returns null if the sweeper gave the row to another worker while it queued.
	@Transactional
	@Query(value = """
			WITH renewed AS (
			     UPDATE reminder
			        SET lease_expires_at = now() + interval '60 seconds'
			      WHERE id = :reminderId
			        AND status = 'CLAIMED'
			        AND claimed_by = :workerId
			  RETURNING id, attempt_count)
			INSERT INTO reminder_attempt (reminder_id, attempt_no, worker_id)
			SELECT id, attempt_count, :workerId FROM renewed
			RETURNING id""", nativeQuery = true)
	Long openAttempt(long reminderId, String workerId);

	@Transactional
	@Modifying
	@Query(value = """
			UPDATE reminder_attempt
			   SET finished_at = now(), outcome = :outcome, provider_ref = :providerRef, error = :error
			 WHERE id = :id""", nativeQuery = true)
	void closeAttempt(long id, String outcome, String providerRef, String error);

}
