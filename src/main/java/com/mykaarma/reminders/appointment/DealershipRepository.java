package com.mykaarma.reminders.appointment;

import java.util.Optional;
import org.springframework.data.repository.Repository;

// Read-only on purpose: dealerships are seeded, the API never writes them.
public interface DealershipRepository extends Repository<Dealership, Long> {

	Optional<Dealership> findByExternalId(String externalId);

}
