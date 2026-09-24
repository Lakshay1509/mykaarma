package com.mykaarma.reminders.appointment;

import com.mykaarma.reminders.reminder.ReminderRepository;
import com.mykaarma.reminders.reminder.ReminderResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/appointments")
public class AppointmentController {

	private final AppointmentService service;

	private final ReminderRepository reminders;

	public AppointmentController(AppointmentService service, ReminderRepository reminders) {
		this.service = service;
		this.reminders = reminders;
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
		return AppointmentResponse.from(service.find(id));
	}

	@PatchMapping("/{id}")
	AppointmentResponse reschedule(@PathVariable UUID id, @RequestHeader("If-Match") int version,
			@Valid @RequestBody RescheduleRequest request) {
		return AppointmentResponse.from(service.reschedule(id, version, request.scheduledAt()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void cancel(@PathVariable UUID id) {
		service.cancel(id);
	}

	@GetMapping("/{id}/reminders")
	List<ReminderResponse> reminders(@PathVariable UUID id) {
		return reminders.findByAppointmentOrderByDueAt(service.find(id)).stream().map(ReminderResponse::from).toList();
	}

	record RescheduleRequest(@NotNull OffsetDateTime scheduledAt) {
	}

}
