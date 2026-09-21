package com.mykaarma.reminders.appointment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.ZoneId;

@Entity
public class Dealership {

	@Id
	private Long id;

	private String externalId;

	private String name;

	private ZoneId timezone;

	protected Dealership() {
	}

	public String getExternalId() {
		return externalId;
	}

	public String getName() {
		return name;
	}

	public ZoneId getTimezone() {
		return timezone;
	}

}
