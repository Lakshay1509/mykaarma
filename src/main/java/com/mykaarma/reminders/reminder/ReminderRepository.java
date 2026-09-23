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
	// The attempt cap lives here and not in scheduleRetry. A send that keeps dying
	// mid-flight won't succeed on a seventh try (§9 FM-9), but a provider outage ends,
	// and a reminder that is still useful should keep retrying until it does (FM-5).
	@Transactional
	@Query(value = """
			WITH released AS (
			     UPDATE reminder
			        SET status = CASE WHEN attempt_count > 5 THEN 'DEAD' ELSE 'PENDING' END,
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
	@Transactional
	@Modifying
	@Query(value = """
			UPDATE reminder
			   SET status = 'SENT', sent_at = now(), lease_expires_at = NULL
			 WHERE id = :id
			   AND status = 'CLAIMED'
			   AND claimed_by = :workerId""", nativeQuery = true)
	int markSent(long id, String workerId);

	// The claim has already counted this attempt, so attempt_count - 1 makes the first
	// retry wait 30s (§7.5).
	@Transactional
	@Modifying
	@Query(value = """
			UPDATE reminder
			   SET status = 'PENDING', claimed_by = NULL, lease_expires_at = NULL, last_error = :error,
			       due_at = now() + least(interval '30 seconds' * power(2, attempt_count - 1), interval '15 minutes')
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
			   SET status = 'SKIPPED_LATE', lease_expires_at = NULL
			 WHERE id = :id
			   AND status = 'CLAIMED'
			   AND claimed_by = :workerId""", nativeQuery = true)
	void markSkipped(long id, String workerId);

}
