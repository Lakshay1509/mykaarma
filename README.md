# Appointment Booking & Reminder Service

Vehicle service appointment booking with two reminders per appointment (24h and 2h
before), delivered **at most once**, provably.

- [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md) — the design and its rationale
- [`TODO.md`](./TODO.md) — build plan

## Run

```bash
docker compose up -d          # Postgres 16 + app + Prometheus; the DB survives `down`, only `down -v` wipes it
./seed.sh                     # demo appointments through the API; each run adds a batch
curl localhost:8080/actuator/health
```

Prometheus is at <http://localhost:9090>; try `reminder_lag_seconds`.

Local runs load three demo dealerships: `DLR-0042` (Chicago), `DLR-0007` (New York),
`DLR-0105` (Phoenix, no DST).

## Develop

```bash
source env.sh                 # JDK 25 on PATH, this shell only
./mvnw spring-boot:run        # app only, expects Postgres on :5432
./mvnw test                   # Testcontainers starts a real Postgres — Docker must be running
./mvnw verify                 # test + spotless
```

## Status

Phase 0 — skeleton. See `TODO.md`.
