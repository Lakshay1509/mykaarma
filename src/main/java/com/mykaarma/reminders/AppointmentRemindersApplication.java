package com.mykaarma.reminders;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AppointmentRemindersApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppointmentRemindersApplication.class, args);
	}

	// Injected rather than read inline, so tests can freeze time.
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
