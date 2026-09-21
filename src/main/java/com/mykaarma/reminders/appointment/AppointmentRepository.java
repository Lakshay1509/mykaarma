package com.mykaarma.reminders.appointment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

	Optional<Appointment> findByPublicId(UUID publicId);

	Optional<Appointment> findByDealershipAndIdempotencyKey(Dealership dealership, String idempotencyKey);

}
