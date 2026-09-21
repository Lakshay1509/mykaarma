package com.mykaarma.reminders.appointment;

import com.mykaarma.reminders.appointment.Appointment.Channel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

// scheduledAt must carry an offset. Without one we would have to guess the zone,
// and a wrong guess sends every reminder an hour or more off.
//
// Free-text fields reject U+0000. Postgres can't store it, so it would fail at the
// INSERT as a 500, and clients treat a 5xx as safe to retry.
public record CreateAppointmentRequest(
		@NotBlank @Pattern(regexp = NO_NUL, message = NUL) String dealershipId,
		@NotNull @Valid Customer customer,
		@NotNull @Valid Vehicle vehicle,
		@NotBlank @Size(max = 64) @Pattern(regexp = NO_NUL, message = NUL) String serviceType,
		@NotNull OffsetDateTime scheduledAt) {

	public record Customer(
			@NotBlank @Size(max = 200) @Pattern(regexp = NO_NUL, message = NUL) String name,
			@Pattern(regexp = "\\+[1-9]\\d{1,14}", message = "must be E.164, e.g. +14155550137") String phone,
			@Email @Size(max = 320) String email,
			@NotNull Channel channel) {

		@AssertTrue(message = "SMS needs a phone, EMAIL needs an email")
		public boolean isContactPresentForChannel() {
			String contact = channel == Channel.SMS ? phone : email;
			return channel == null || (contact != null && !contact.isBlank());
		}

	}

	public record Vehicle(
			@Pattern(regexp = "[A-HJ-NPR-Z0-9]{17}", message = "must be a 17-character VIN") String vin,
			@NotBlank @Size(max = 200) @Pattern(regexp = NO_NUL, message = NUL) String description) {
	}

	static final String NO_NUL = "[^\\x00]*";

	static final String NUL = "must not contain U+0000";

}
