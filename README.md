# Appointment Booking & Reminder Service

Vehicle service appointment booking with two reminders per appointment (24h and 2h
before), delivered **at most once**, provably.

- [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md) — the design and its rationale
- [`TODO.md`](./TODO.md) — build plan

## Run

```bash
docker compose up -d          # Postgres 16 + app
curl localhost:8080/actuator/health
```

## Develop

```bash
source env.sh                 # JDK 25 on PATH, this shell only
./mvnw spring-boot:run        # app only, expects Postgres on :5432
./mvnw test                   # Testcontainers starts a real Postgres — Docker must be running
./mvnw verify                 # test + spotless
```

## Status

Phase 0 — skeleton. See `TODO.md`.
