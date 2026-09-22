package com.mykaarma.reminders.reminder;

import com.mykaarma.reminders.appointment.Appointment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
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

}
