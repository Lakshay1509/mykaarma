package com.mykaarma.reminders.appointment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/appointments")
public class AppointmentController {

	private final AppointmentService service;

	private final AppointmentRepository appointments;

	public AppointmentController(AppointmentService service, AppointmentRepository appointments) {
		this.service = service;
		this.appointments = appointments;
	}

	@PostMapping
	ResponseEntity<AppointmentResponse> create(
			@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
			@Valid @RequestBody CreateAppointmentRequest request) {
		AppointmentService.Booking booking = service.create(request, idempotencyKey);
		AppointmentResponse body = AppointmentResponse.from(booking.appointment());
		if (!booking.created()) {
			return ResponseEntity.ok(body);
		}
		return ResponseEntity.created(URI.create("/v1/appointments/" + body.id())).body(body);
	}

	@GetMapping("/{id}")
	AppointmentResponse get(@PathVariable UUID id) {
		return appointments.findByPublicId(id)
			.map(AppointmentResponse::from)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No appointment with id " + id));
	}

}
