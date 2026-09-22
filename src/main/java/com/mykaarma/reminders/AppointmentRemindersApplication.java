package com.mykaarma.reminders;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class AppointmentRemindersApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppointmentRemindersApplication.class, args);
	}

	// Injected rather than read inline, so tests can freeze time.
	@Bean
	Clock clock(JdbcTemplate jdbc) {
		return new Clock() {

			@Override
			public Instant instant() {
				return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
			}

			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				throw new UnsupportedOperationException("time stays an Instant (§6.1)");
			}

		};
	}

}
