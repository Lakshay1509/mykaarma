# TODO — Appointment Booking & Reminder Service

Design: [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md) · Due: 7 days from receipt · ~26h of work

**How we split it.** The brief says *"you can explain and defend every line you submit."*
So the rule is: **you own anything an interviewer will drill into**, I own plumbing
you can read in 30 seconds. Anything marked 🧠 is yours because you'll be asked
about it, not because it's hard.

| | |
|---|---|
| 👤 | **You** — write it, or write it with me pairing |
| 🤖 | **Me** — I write it, you review before it's committed |
| 🧠 | **Interview-critical** — do not merge until you can explain it unprompted |
| ⏸️ | **Checkpoint** — stop, verify, then continue |

---

## Phase 0 — Skeleton  *(~2h)*  ✅ **done**

Goal: `docker compose up` gives a running app on an empty schema.

- [x] 🤖 Spring Boot 4.1.1 / Java 25 / **Maven** — `web`, `data-jpa`, `validation`, `actuator`, `flyway`, `postgresql`
- [x] 🤖 `docker-compose.yml` — Postgres 16 + the app, one command to start
- [x] 🤖 Testcontainers wired for tests — `spring-boot-testcontainers` + `org.testcontainers:postgresql`
- [x] 🤖 JDK 25 installed at `~/.jdks/jdk-25.0.4.1+1` + `env.sh` *(global JDK 17 untouched — React Native needs it)*
- [x] 👤 `source env.sh` in any terminal you build from — `java -version` must say 25
- [x] 👤 `git init`, public GitHub repo, first commit
- [x] 🤖 `.gitignore`, `README.md` stub, MIT licence

**Acceptance**
- `docker compose up` → app healthy at `/actuator/health`
- `./mvnw test` runs green with zero tests
- Testcontainers starts a real Postgres (**not H2** — H2 has no `SKIP LOCKED`, see Phase 6)

---

## Phase 1 — Booking  *(~4h)*  ✅ **done**

Goal: `POST /appointments` persists a booking. No reminders yet.

- [x] 🤖 Flyway `V1__baseline.sql` — `dealership`, `appointment` (§4 of the design)
- [x] 🤖 JPA entities + repositories
- [x] 🤖 `POST /v1/appointments` + `GET /v1/appointments/{id}`
- [x] 👤 🧠 Request validation — future-dated, ≤365d out, E.164 phone, channel matches contact
- [x] 👤 🧠 **Idempotent creation** — `Idempotency-Key` header, `UNIQUE (dealership_id, idempotency_key)`, replay returns the original
- [x] 🤖 RFC 7807 error handling (`@RestControllerAdvice`)
- [x] 🤖 Seed 3 dealerships across 3 timezones for local testing

**Acceptance**
- Two `POST`s with the same key → **one** appointment, `201` then `200`
- Same key + different body → `409`
- Past date → `422` with a readable message

> 🧠 **Be able to say:** *"Without this, a client retry on a lost response creates a second appointment and the customer gets the 24-hour reminder twice. Every send was unique and the requirement is still broken — because it's about the customer's phone, not my table."*

---

## Phase 2 — Reminders exist  *(~2h)*

Goal: booking an appointment writes two reminder rows. Nothing sends yet.

- [x] 🤖 Flyway `V2__reminder.sql` — `reminder` + `reminder_attempt` + the two partial indexes
- [x] 👤 🧠 **`UNIQUE (appointment_id, reminder_type)`** — write this line yourself *(widened with `appointment_version` in V3, see Phase 5)*
- [x] 👤 🧠 `due_at` = `scheduledAt.minus(lead)` on an `Instant` *(§6.1 — DST-correct by construction)*
- [x] 👤 🧠 Past-due at creation → `SKIPPED_LATE`, not `PENDING` *(§8.2 — the 3-hours-notice case)*
- [x] 🤖 Deterministic idempotency key — name-based UUID (v3) from `(public_id, reminder_type)` *(plus `appointment_version` since V3)*
- [x] 🤖 Reminders inserted **in the same transaction** as the appointment
- [x] 🤖 `GET /v1/appointments/{id}/reminders` *(the proof endpoint + demo shot)*

**Acceptance**
- Book 25h out → 2 reminders `PENDING`, correct `due_at`
- Book 3h out → T24H is `SKIPPED_LATE`, T2H is `PENDING`
- Book 30m out → both `SKIPPED_LATE`
- Kill the app mid-insert → **no** appointment without its reminders

### ⏸️ Checkpoint A
- [ ] `./mvnw test` green
- [ ] Walk the schema out loud: every column, why it exists, what breaks without it
- [x] Send the reschedule question email *(answered: a rescheduled customer gets reminders for the new time — §14 Q2)*

---

## Phase 3 — Dispatch  *(~4h)*  ← the core

Goal: reminders actually go out.

- [x] 👤 🧠 **The claim query** — `FOR UPDATE SKIP LOCKED`, `ORDER BY due_at`, `LIMIT 200`, lease 60s
- [x] 👤 🧠 `@Scheduled(fixedDelay = 1000)` dispatcher — **no ShedLock, deliberately**
- [x] 👤 🧠 Lease sweeper — expired `CLAIMED` → `PENDING` (30s, **no** ShedLock: idempotent + `SKIP LOCKED`, §6.3)
- [x] 🤖 `NotificationSender` interface + `LoggingNotificationSender` (masked recipient, measured Twilio/SES latency)
- [x] 👤 🧠 Send outside the transaction — claim (TX1) → send → settle (TX2) *(batch sent concurrently on virtual threads; settle guarded by `claimed_by`)*
- [x] 🤖 Message body rendering using `local_tz` *("Tue 2:00 PM", not UTC)*
- [x] 🤖 `worker.enabled` profile flag — one JAR, two roles

**Acceptance**
- A due reminder logs a `NOTIFICATION` line and flips to `SENT`
- Run two app instances → each handles different reminders, none shared
- `kill -9` a worker mid-send → reminder recovers 60–90s later, still one `SENT` row *(rows sent but not yet settled go out twice with the same key, §7.3)*

> 🧠 **The three things you must be able to draw:** why all workers poll at once (`SKIP LOCKED`), why the lease has a timer (a dead worker would hold the row forever), and why the network call is outside the transaction (a slow vendor would eat the whole connection pool).

---

## Phase 4 — Failure handling  *(~3h)*

- [x] 👤 🧠 Three outcomes: `OK` / `RETRYABLE` / `PERMANENT` — and why permanent never retries *(exhaustive `switch`; every settle keeps the `claimed_by` guard)*
- [x] 🤖 Exponential backoff, `min(30s · 2ⁿ, 15min)`, ±20% jitter *(one SQL expression on the DB clock, in `scheduleRetry`)*
- [x] 🤖 `attempt_count > 5` → `DEAD` + error log *(poison-row cap, applied on lease expiry only and never to `RETRYABLE`; see §7.5)*
- [x] 👤 🧠 **Useful-lead-time suppression** *(§8.3 — measure against the appointment, not the reminder; checked right before each send, also bounds `RETRYABLE`)*
- [x] 🤖 `reminder_attempt` row written per attempt *(opened before the send, closed after the settle; skipped reminders get none)*
- [x] ~~🤖 Resilience4j circuit breaker around the sender~~ *(deferred: the stub sender can't fail, and backoff plus useful lead time already get reminders through an outage; listed in §15 "With another week")*
- [x] 👤 🧠 **Cap in-flight sends at the provider's limit** — per-worker `Semaphore` (Twilio `429` = too many concurrent requests), rate limiter for SES (~14/s default quota); size from config (§6.4) *(one `Semaphore` per channel; the SES rate limiter is deferred, see the note in `application.properties`)*

**Acceptance**
- Sender throws timeout → back to `PENDING` with a later `due_at`, `attempt_count` incremented
- Sender returns invalid-number → `DEAD` immediately, no retry
- T2H reminder 2h overdue → `SKIPPED_LATE`, never sent
- T24H reminder 4h overdue → **still sends** (same lateness, opposite answer)

---

## Phase 5 — Lifecycle  *(~2h)*

- [x] 🤖 `DELETE /v1/appointments/{id}` — pending reminders → `CANCELLED` *(`PENDING` and `CLAIMED`, one conditional `UPDATE`, so a row a worker just marked `SENT` is left alone)*
- [x] 🤖 `PATCH /v1/appointments/{id}` — reschedule, `If-Match: <version>` *(`version` is now in the response; the version-mismatch `409` came with it)*
- [x] 👤 🧠 Reschedule writes a **fresh pair for the new time** through the **same constructor as booking**, so the Phase 2 past-check applies to both (one rule, two callers) *(unsent old reminders → `CANCELLED`; V3 widens the key to `(appointment_id, reminder_type, appointment_version)` — client confirmed re-notifying on reschedule, §14 Q2)*
- [x] 👤 🧠 Already-`SENT` reminders are never re-sent or reopened *(the old row stays `SENT`; the new-time reminder is a new row with its own idempotency key)*
- [x] 🤖 `409` on version mismatch; `409` on rescheduling a cancelled appointment *(anything not `BOOKED`; rescheduling to the same time is a no-op `200`, so nobody is re-reminded for nothing)*

**Acceptance**
- Cancel → pending reminders `CANCELLED`, sent ones untouched
- Reschedule +3 days → unsent reminders `CANCELLED`, a new `PENDING` pair for the new time, sent ones untouched
- Reschedule to 1h from now → both new reminders are `SKIPPED_LATE`

### ⏸️ Checkpoint B
- [x] Full happy path works end to end by hand *(`docker compose up`: book → replay → T2H `SENT` on time → reschedule → stale `409` → cancel → `409`)*
- [x] All four lifecycle transitions verified in `psql` *(skipped-late at booking, sent, rescheduled into a v1 pair, cancelled; duplicate query → 0 rows)*
- [x] Push to GitHub — you should have something demoable even if you stopped here

---

## Phase 6 — Proof  *(~4h)*  ← what's actually being graded

- [x] 👤 🧠 **`tenWorkersRacingOneReminder_sendExactlyOnce`** — the most important test in the repo *(`DispatcherConcurrencyTest`: ten `Dispatcher`s, own `workerId` each, released by one barrier; asserts one send and `attempt_count = 1`)*
- [x] 👤 🧠 **Crash test** — sender records then throws; expire lease; assert 2 attempts, same key, **1** `SENT` *(in `DispatcherConcurrencyTest`: the first worker's send throws after the provider has the message; once the lease is expired, a second worker sweeps and resends. Goes red if the key is regenerated per claim)*
- [x] 👤 DST tests — Mar 8 2026 spring-forward, Nov 1 2026 fall-back, nonexistent local time → `422` *(Spring-forward is covered by `ReminderTypeTest`. The `422` test uses the 2027 gap, since Mar 8 2026 is already in the past for the frozen test clock. Fall-back books 01:30 at -05:00 and at -06:00 and gets two instants an hour apart; it goes red if the service resolves the local time by zone and ignores the offset)*
- [x] 🤖 Idempotent-`POST` test, cancel test, reschedule test *(already in `AppointmentControllerTest` from Phases 1 and 5: `retryWithSameKey_…`, `sameKeyWithDifferentDetails_isRejected`, `cancel_stopsUnsentRemindersButLeavesSentOnesAlone`, `reschedule_remindsForTheNewTime_andLeavesTheSentOneSent`, `rescheduleToOneHourAway_…`)*
- [x] 🤖 Randomised 10k-appointment workload with injected failures *(`DispatcherConcurrencyTest#randomWorkload…`: 20k reminders, ten workers, 10% timeouts and 2% crashes mid-send; asserts nothing is left unfinished, each accepted key has exactly one `SENT` row, and every call carries a stored key. Goes red with a sweeper that never frees crashed rows (419 stuck) or a timeout settled as `SENT`)*
- [x] 🤖 CI invariant check: the duplicate-detection `GROUP BY … HAVING count(*) > 1` returns **0 rows** *(the workload test's last assertion. It groups by the `UNIQUE` columns, so it can't return rows while the constraint exists; `ReminderSchemaTest#secondReminderOfTheSameType_isRejectedByTheDatabase` proves the constraint fires)*
- [x] 👤 Confirm every test runs on **Testcontainers Postgres** — grep the repo for `h2`, expect nothing *(no H2 in any build or config file or in the 151 resolved test dependencies; the one hit in code is `HH24` in a SQL date format. Every `@SpringBootTest` imports `TestcontainersConfiguration`, and the tests without it touch no database)*

**Acceptance**
- `./mvnw test` green
- The concurrency test **fails** if you delete `FOR UPDATE SKIP LOCKED` from the query *(verified: all ten workers claimed, `SENT:10`. With only `SKIP LOCKED` deleted it stays green, because workers queue instead of double-claiming; `ReminderClaimTest#rowsLockedByOneWorker_…` goes red instead)*

> 🧠 That last bullet is the one to actually do. Break it on purpose, watch it go red, put it back.

---

## Phase 7 — Operations  *(~2h)*

- [x] 🤖 Micrometer + `/actuator/prometheus` *(the actuator starter already brings Micrometer, so this is the Prometheus registry plus exposing `health,prometheus`. Nothing else on `/actuator` is served)*
- [x] 👤 🧠 **`reminder_lag_seconds`** gauge — oldest overdue pending reminder *(`ReminderRepository#lagSeconds`, registered on every node rather than in the `Dispatcher`, so it keeps reporting when every worker is down. `ReminderLagTest` goes red if the `PENDING` filter goes or `min` becomes `max`. A retry moves `due_at` forward, so a provider that fails fast shows in the error-rate counters, not here)*
- [x] 🤖 Counters: `reminders_sent_total{type,outcome}`, `_dead_`, `_skipped_late_`, send-duration histogram *(one `reminder_send_seconds{type,channel,outcome}` timer: its `_count` is the sends counter, so nothing separate can drift from it. `reminders_dead_total{reason=crashes|permanent}`, because only crashes should page. `reminders_skipped_late_total` counts only the dispatcher's skip, not short-notice bookings skipped at creation. Every increment uses the number of rows the update changed, so a worker that lost its claim adds nothing. `DispatcherTest` and `SendOutcomeTest` go red without the increments)*
- [x] 🤖 Liveness/readiness probes *(nothing to write: `management.endpoint.health.probes.enabled=true` already serves both. They deliberately leave the database out: a DB outage would otherwise pull every pod from the load balancer at once, and clients would get connection errors instead of the `503` FM-4 promises. Overall `/actuator/health` still goes red)*
- [x] ~~🤖 Nightly invariant + orphan check jobs (ShedLock)~~ *(cut: the duplicate query can't return rows while `uq_reminder` exists, and the orphan check looks for what the booking transaction already prevents, which `failedReminderInsert_leavesNoAppointmentBehind` tests. Neither is worth adding ShedLock and a migration for. §10.2 keeps them as production jobs)*

**Acceptance**
- Stop the workers for 2 minutes → `reminder_lag_seconds` climbs, then returns to ~0 on restart

> 🧠 **Why this metric:** it's the SLO expressed as a number. Dispatcher down, provider down, DB slow, too few workers — all four show up in it. One alert catches failures you didn't predict.

---

## Phase 8 — Ship  *(~3h)*

- [ ] 👤 🧠 **README** — how to run, design decisions, next week *(start from §15)*, open questions *(brief point 8)*
- [ ] 👤 Design diagram — export §3 of the design doc *(Excalidraw or Mermaid)*
- [ ] 🤖 `demo.http` / Postman collection with every call in order
- [ ] 🤖 `POST /test/advance-clock` (dev profile only) so the video doesn't wait 24h
- [ ] 👤 **Demo video, strictly under 5 minutes** — script is in `SYSTEM_DESIGN.md` §13.1
- [ ] 👤 Final read of every file — if you can't defend a line, delete or rewrite it
- [ ] 👤 Reply to the email with repo + video links

**Video must show** *(brief deliverable 1)*
- create an appointment via curl
- service logs: appointment created, reminders sent
- database rows: the appointment, and the reminders

> The money shot: replay the same `Idempotency-Key` → no new rows, then run the duplicate query → **0 rows**. That's "provable" on camera in 15 seconds.

---

## Working in parallel

Safe to run at the same time:

| You | Me |
|---|---|
| Phase 0 Docker check + repo setup | Phase 0 project skeleton |
| Phase 2 🧠 items | Phase 1 boilerplate |
| Phase 3 claim query + dispatcher | Phase 4 backoff + circuit breaker |
| Phase 6 the two 🧠 tests | Phase 6 the routine tests |
| Phase 8 README + video | Phase 7 metrics + Phase 8 demo collection |

**Must be sequential:** Flyway migrations (one writer — tell me before you add one), and Phase 3 before Phase 4.

---

## Cut list — if you run short on time

Drop in this order. Everything above the line still satisfies the brief:

1. Circuit breaker *(backoff alone is enough)*
2. `reminder_attempt` table *(status on `reminder` still proves it)*
3. Prometheus metrics *(logs suffice for the demo)*
4. Reschedule *(cancel is the one that prevents a wrong message)*
5. Read replica / partitioning *(design-doc only — never build this)*

**Never cut:** the unique constraint, the lease, the concurrency test, the README.

---

## Risks

| Risk | Mitigation |
|---|---|
| H2 sneaks into tests → concurrency test passes while proving nothing | Phase 6 grep, and deliberately break `SKIP LOCKED` to watch it fail |
| Video runs over 5 minutes | Script it, use the clock-advance endpoint, rehearse once |
| Scope creep into a booking system | Slot capacity, auth and billing are out of scope *(§1.4)* — say so in the README |
| Can't explain something on submission day | The 🧠 rule: don't merge what you can't explain unprompted |
