package com.mykaarma.reminders.notification;

public record SendResult(Outcome outcome, String providerRef, String error) {

	public enum Outcome {
		OK, RETRYABLE, PERMANENT
	}

	public static SendResult ok(String providerRef) {
		return new SendResult(Outcome.OK, providerRef, null);
	}

}
