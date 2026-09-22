package com.mykaarma.reminders;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AppointmentRemindersApplicationTests {

	@Test
	@Transactional
	void productionClock_isTheDatabaseClock(@Qualifier("clock") Clock clock, @Autowired JdbcTemplate jdbc) {
		assertThat(clock.instant()).isEqualTo(jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant());
	}

}
