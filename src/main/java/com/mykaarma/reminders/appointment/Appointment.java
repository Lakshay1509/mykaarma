package com.mykaarma.reminders.appointment;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

// The dispatcher reads the appointments of up to 200 claimed reminders inside its claim
// transaction: one IN query for the whole batch instead of 200 lookups.
@Entity
@BatchSize(size = 200)
public class Appointment {

	public enum Channel {
		SMS, EMAIL
	}

	public enum Status {
		BOOKED, CANCELLED, COMPLETED, NO_SHOW
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private UUID publicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Dealership dealership;

	private String customerName;

	private String customerPhone;

	private String customerEmail;

	@Enumerated(EnumType.STRING)
	private Channel channel;

	private String vehicleVin;

	private String vehicleDescription;

	private String serviceType;

	private Instant scheduledAt;

	private ZoneId localTz;

	@Enumerated(EnumType.STRING)
	private Status status;

	private String idempotencyKey;

	@Version
	private int version;

	@UpdateTimestamp(source = SourceType.DB)
	private Instant updatedAt;

	protected Appointment() {
	}

	public Appointment(Dealership dealership, String customerName, String customerPhone, String customerEmail,
			Channel channel, String vehicleVin, String vehicleDescription, String serviceType, Instant scheduledAt,
			String idempotencyKey) {
		// Assigned here so it's known before the insert. The column default only
		// covers rows inserted by hand.
		this.publicId = UUID.randomUUID();
		this.dealership = dealership;
		this.customerName = customerName;
		this.customerPhone = customerPhone;
		this.customerEmail = customerEmail;
		this.channel = channel;
		this.vehicleVin = vehicleVin;
		this.vehicleDescription = vehicleDescription;
		this.serviceType = serviceType;
		this.scheduledAt = scheduledAt;
		this.localTz = dealership.getTimezone();
		this.status = Status.BOOKED;
		this.idempotencyKey = idempotencyKey;
	}

	// What a retry must match to count as the same booking. Fields the server
	// assigns (public id, status, local tz) are left out.
	boolean sameBookingAs(Appointment other) {
		return Objects.equals(customerName, other.customerName) && Objects.equals(customerPhone, other.customerPhone)
				&& Objects.equals(customerEmail, other.customerEmail) && channel == other.channel
				&& Objects.equals(vehicleVin, other.vehicleVin)
				&& Objects.equals(vehicleDescription, other.vehicleDescription)
				&& Objects.equals(serviceType, other.serviceType) && Objects.equals(scheduledAt, other.scheduledAt);
	}

	public UUID getPublicId() {
		return publicId;
	}

	public Dealership getDealership() {
		return dealership;
	}

	public String getCustomerName() {
		return customerName;
	}

	public String getCustomerPhone() {
		return customerPhone;
	}

	public String getCustomerEmail() {
		return customerEmail;
	}

	public Channel getChannel() {
		return channel;
	}

	public String getVehicleVin() {
		return vehicleVin;
	}

	public String getVehicleDescription() {
		return vehicleDescription;
	}

	public String getServiceType() {
		return serviceType;
	}

	public Instant getScheduledAt() {
		return scheduledAt;
	}

	public ZoneId getLocalTz() {
		return localTz;
	}

	public Status getStatus() {
		return status;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

}
