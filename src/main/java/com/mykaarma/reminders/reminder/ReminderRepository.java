package com.mykaarma.reminders.reminder;

import com.mykaarma.reminders.appointment.Appointment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

	List<Reminder> findByAppointmentOrderByDueAt(Appointment appointment);

}
