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
- [x] 👤 🧠 **`UNIQUE (appointment_id, reminder_type)`** — write this line yourself
- [x] 👤 🧠 `due_at` = `scheduledAt.minus(lead)` on an `Instant` *(§6.1 — DST-correct by construction)*
- [x] 👤 🧠 Past-due at creation → `SKIPPED_LATE`, not `PENDING` *(§8.2 — the 3-hours-notice case)*
- [x] 🤖 Deterministic idempotency key — name-based UUID (v3) from `(public_id, reminder_type)`
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
- [ ] Send the reschedule question email *(draft is in our chat — §14 Q2)*

---

## Phase 3 — Dispatch  *(~4h)*  ← the core

Goal: reminders actually go out.

- [x] 👤 🧠 **The claim query** — `FOR UPDATE SKIP LOCKED`, `ORDER BY due_at`, `LIMIT 200`, lease 60s
- [ ] 👤 🧠 `@Scheduled(fixedDelay = 1000)` dispatcher — **no ShedLock, deliberately**
- [ ] 👤 🧠 Lease sweeper — expired `CLAIMED` → `PENDING` (30s, **with** ShedLock)
- [ ] 🤖 `NotificationSender` interface + `LoggingNotificationSender` (masked recipient)
- [ ] 👤 🧠 Send outside the transaction — claim (TX1) → send → settle (TX2)
- [ ] 🤖 Message body rendering using `local_tz` *("Tue 2:00 PM", not UTC)*
- [ ] 🤖 `worker.enabled` profile flag — one JAR, two roles

**Acceptance**
- A due reminder logs a `NOTIFICATION` line and flips to `SENT`
- Run two app instances → each handles different reminders, none shared
- `kill -9` a worker mid-send → reminder recovers ~60s later, single logical delivery

> 🧠 **The three things you must be able to draw:** why all workers poll at once (`SKIP LOCKED`), why the lease has a timer (a dead worker would hold the row forever), and why the network call is outside the transaction (a slow vendor would eat the whole connection pool).

---

## Phase 4 — Failure handling  *(~3h)*

- [ ] 👤 🧠 Three outcomes: `OK` / `RETRYABLE` / `PERMANENT` — and why permanent never retries
- [ ] 🤖 Exponential backoff, `min(30s · 2ⁿ, 15min)`, ±20% jitter
- [ ] 🤖 `attempt_count > 5` → `DEAD` + error log *(poison-row cap)*
- [ ] 👤 🧠 **Useful-lead-time suppression** *(§8.3 — measure against the appointment, not the reminder)*
- [ ] 🤖 `reminder_attempt` row written per attempt
- [ ] 🤖 Resilience4j circuit breaker around the sender

**Acceptance**
- Sender throws timeout → back to `PENDING` with a later `due_at`, `attempt_count` incremented
- Sender returns invalid-number → `DEAD` immediately, no retry
- T2H reminder 2h overdue → `SKIPPED_LATE`, never sent
- T24H reminder 4h overdue → **still sends** (same lateness, opposite answer)

---

## Phase 5 — Lifecycle  *(~2h)*

- [ ] 🤖 `DELETE /v1/appointments/{id}` — pending reminders → `CANCELLED`
- [ ] 🤖 `PATCH /v1/appointments/{id}` — reschedule, `If-Match: <version>`
- [ ] 👤 🧠 Reschedule recomputes `due_at` and **reuses the Phase 2 past-check** (one rule, two callers)
- [ ] 👤 🧠 Already-`SENT` reminders are never re-sent or reopened
- [ ] 🤖 `409` on version mismatch; `409` on rescheduling a cancelled appointment

**Acceptance**
- Cancel → pending reminders `CANCELLED`, sent ones untouched
- Reschedule +3 days → pending `due_at` values move, sent ones don't
- Reschedule to 1h from now → both pending reminders become `SKIPPED_LATE`

### ⏸️ Checkpoint B
- [ ] Full happy path works end to end by hand
- [ ] All four lifecycle transitions verified in `psql`
- [ ] Push to GitHub — you should have something demoable even if you stopped here

---

## Phase 6 — Proof  *(~4h)*  ← what's actually being graded

- [ ] 👤 🧠 **`tenWorkersRacingOneReminder_sendExactlyOnce`** — the most important test in the repo
- [ ] 👤 🧠 **Crash test** — sender records then throws; expire lease; assert 2 attempts, same key, **1** `SENT`
- [ ] 👤 DST tests — Mar 8 2026 spring-forward, Nov 1 2026 fall-back, nonexistent local time → `422`
- [ ] 🤖 Idempotent-`POST` test, cancel test, reschedule test
- [ ] 🤖 Randomised 10k-appointment workload with injected failures
- [ ] 🤖 CI invariant check: the duplicate-detection `GROUP BY … HAVING count(*) > 1` returns **0 rows**
- [ ] 👤 Confirm every test runs on **Testcontainers Postgres** — grep the repo for `h2`, expect nothing

**Acceptance**
- `./mvnw test` green
- The concurrency test **fails** if you delete `SKIP LOCKED` from the query *(verify this — a test that can't fail proves nothing)*

> 🧠 That last bullet is the one to actually do. Break it on purpose, watch it go red, put it back.

---

## Phase 7 — Operations  *(~2h)*

- [ ] 🤖 Micrometer + `/actuator/prometheus`
- [ ] 👤 🧠 **`reminder_lag_seconds`** gauge — oldest overdue pending reminder
- [ ] 🤖 Counters: `reminders_sent_total{type,outcome}`, `_dead_`, `_skipped_late_`, send-duration histogram
- [ ] 🤖 Liveness/readiness probes
- [ ] 🤖 Nightly invariant + orphan check jobs (ShedLock)

**Acceptance**
- Stop the workers for 2 minutes → `reminder_lag_seconds` climbs, then returns to ~0 on restart

> 🧠 **Why this metric:** it's the SLO expressed as a number. Dispatcher down, provider down, DB slow, too few workers — all four show up in it. One alert catches failures you didn't predict.

---

## Phase 8 — Ship  *(~3h)*

- [ ] 👤 🧠 **README** — how to run, design decisions, next week, open questions *(brief point 8)*
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
