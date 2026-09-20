# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Vehicle service appointment booking + reminder dispatch. Two reminders per appointment
(24h and 2h before), delivered **at most once**, provably.

- [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md) — the design and its rationale. **Source of truth.**
  Section numbers below refer to it.
- [`TODO.md`](./TODO.md) — phased build plan with ownership split.

The codebase is being built against that design. When code and design disagree, fix
one of them deliberately — do not let them drift.

## Commands

```bash
docker compose up -d                       # Postgres + app
./mvnw spring-boot:run                     # app only (expects Postgres on :5432)
./mvnw test                                # full suite (starts Testcontainers Postgres)
./mvnw test -Dtest=DispatcherConcurrencyTest
./mvnw test -Dtest='DispatcherConcurrencyTest#tenWorkersRacingOneReminder_sendExactlyOnce'
./mvnw verify                              # test + spotless + formatting checks
./mvnw spotless:apply                      # fix formatting
```

**Toolchain:** Java 25 (LTS), Spring Boot 4.1.x, Maven via the bundled `./mvnw` wrapper.

Run `source env.sh` first — it puts JDK 25 on `PATH` **for this shell only**. The global
`JAVA_HOME` in `~/.bashrc` is deliberately pinned to JDK 17 for React Native/Android and
must not be changed. If `./mvnw` reports `release version 25 not supported`, you forgot
to source it.

Tests require a running Docker daemon. There is no in-memory test profile — see Testing.

## Architecture

Four pieces: API nodes, Postgres, dispatcher workers, `NotificationSender`. The parts
that aren't obvious from reading any single file:

**The queue is a table.** No broker. `reminder` rows are claimed with
`SELECT … FOR UPDATE SKIP LOCKED`. The row that schedules a reminder and the row that
records it was sent are the same row — one source of truth, so there is no state that
can drift into a duplicate or a miss (§12.2).

**One JAR, two profiles.** API and worker are the same artifact, switched by
`app.worker.enabled`. This is what lets an appointment and its two reminders be written
in a single local transaction (§3).

**Claim → send → settle.** Dispatch is three steps, and the network call sits between
two short transactions rather than inside one (§7.4).

**Reminder rows are materialised at booking time**, in the appointment's transaction.
An appointment can never exist without its reminders, so there is no reconciliation
job and no hydration step (§4.2).

## Invariants

Breaking any of these breaks the assignment. Do not "simplify" them without reading
the linked section first.

1. **`UNIQUE (appointment_id, reminder_type)`** stays on `reminder`. This constraint
   *is* the no-duplicates requirement (§7.3). Nothing transitions out of `SENT`.
2. **Never hold a transaction across a `NotificationSender` call.** One slow vendor
   would exhaust the connection pool and take down the API too (§9 FM-10).
3. **The dispatcher has no ShedLock and must not get one.** All workers poll
   concurrently; `SKIP LOCKED` makes that safe and is the scaling mechanism. ShedLock
   belongs only on genuinely singleton housekeeping jobs (§6.2).
4. **Due-time comparisons use the database clock**, never `Instant.now()` from the app.
   Multiple workers mean multiple clocks; a fast node would silently send early (§9 FM-7).
5. **`due_at` arithmetic happens on `Instant`**, never on `ZonedDateTime`/`LocalDateTime`.
   Elapsed-time subtraction is DST-correct by construction; calendar math is not (§6.1).
6. **The idempotency key is deterministic** — UUIDv5 of `(public_id, reminder_type)`,
   computed once and stored. Never regenerate it on retry; that defeats its purpose (§7.3).
7. **Partial indexes stay partial.** `WHERE status = 'PENDING'` is what keeps the claim
   query flat as the table grows past 100M rows (§4).
8. **Reminders due in the past at creation time are `SKIPPED_LATE`, not `PENDING`.**
   Otherwise a short-notice booking fires "your appointment is tomorrow" immediately (§8.2).

## Code style

Written to be defended line by line in a review. Bias to less code.

- **Comments explain *why*, never *what*.** If a comment restates the code, delete it.
  The comments worth keeping are the ones that stop someone "fixing" a deliberate
  choice — the four-line note above the out-of-transaction send is the model.
- **No Javadoc on self-evident methods.** `/** Gets the id. */` is noise.
- No interface with a single implementation, except `NotificationSender` — that seam is
  required by the brief and will get a real adapter.
- Constructor injection, `final` fields, no `@Autowired` on fields.
- Java `record` for DTOs and value types. Entities stay JPA classes.
- Package by feature (`appointment`, `reminder`, `notification`), not by layer.
- Validation at the edge with Bean Validation; invariants in the schema as constraints.
  Application validation protects the user, database constraints protect the data.
- Fail fast and loudly. No catch-and-log-and-continue on a write path.
- Log recipients masked (`+1415•••0137`). PII does not go to an aggregated log store.

## Testing

- **Testcontainers Postgres only. H2 is banned.** H2 does not implement `SKIP LOCKED`,
  so the concurrency test would pass green while proving nothing about production
  behaviour — false confidence in the one guarantee being graded. Any PR introducing
  an H2 dependency is wrong.
- Test names are behaviour assertions: `tenWorkersRacingOneReminder_sendExactlyOnce`,
  not `testDispatcher2`.
- Assert on observable outcomes — rows, sender invocations, status transitions — not on
  internal call order. Mockito `verify` on a collaborator's sequence is a refactor trap.
- Time is injected via `Clock`, never `Instant.now()` inline, so time-dependent tests
  don't sleep.
- Every failure mode in §9 that has a code path gets a test. The ones marked *accepted*
  in that table do not.
- A test that cannot fail is not a test. When adding one for a concurrency or
  correctness invariant, break the mechanism on purpose once and confirm it goes red.

## Scope

Out of scope, deliberately (§1.4): slot/bay capacity, technician assignment, real SMS
or email delivery, customer auth, billing, DMS integration. Reminders are logged by a
stub sender, per the brief.

Do not add caching, sharding, message brokers, or read replicas. At 10× the stated load
this is ~70 writes/sec — about 2% of one Postgres node (§2). §10.4 lists the measured
thresholds at which those become warranted; none are close.
