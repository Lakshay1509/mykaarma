package com.mykaarma.reminders;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Turns every Spring MVC error and every ResponseStatusException into
// application/problem+json (RFC 9457, which replaced RFC 7807).
// https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

	// The Idempotency-Key header carries a constraint, so Spring validates the whole
	// method call and reports body errors here, not as MethodArgumentNotValidException.
	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<String> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(result -> result.getResolvableErrors()
				.stream()
				.map(error -> (error instanceof FieldError field ? field.getField()
						: result.getMethodParameter().getParameterName()) + ": " + error.getDefaultMessage()))
			.toList();
		ex.getBody().setProperty("errors", errors);
		return super.handleHandlerMethodValidationException(ex, headers, status, request);
	}

}
