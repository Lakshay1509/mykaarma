# Appointment Booking & Reminder Service: System Design

Status: design v1 · Stack: Spring Boot 4.1 (Java 25) + PostgreSQL 16
Target: 500,000 appointments/day across 500 dealerships (10× the brief's 50,000)

---

## 0. TL;DR: the three main decisions

1. The queue is a table. There is no Kafka, Redis, or Quartz. Workers claim `reminder`
   rows with `SELECT … FOR UPDATE SKIP LOCKED`. That is one index and one query with no
   second source of truth, and reschedule and cancel stay a plain `UPDATE`.
2. A database constraint enforces "never twice".
   `UNIQUE (appointment_id, reminder_type, appointment_version)` makes a duplicate physically unrepresentable.
   The leases, idempotency keys, and attempt logs are there so a reminder is not *lost*.
3. 10× load is not a throughput problem. 500k/day averages 5.8 writes/sec. The constraints
   that shape the design are (a) ~31,000 reminders falling due in the same second at
   slot boundaries, and (b) 365M reminder rows/year. Batching and range partitioning
   handle both, without a distributed system.

---

## 1. Requirements

### 1.1 Functional (from the brief)

| # | Requirement | Where it's handled |
|---|---|---|
| F1 | `POST /appointments` creates an appointment with scheduled time + customer contact | Section 5 |
| F2 | Reminder 24h before the appointment | Sections 4, 6 |
| F3 | Reminder 2h before the appointment | Sections 4, 6 |
| F4 | Notifications go through a stub `NotificationSender` (log only) | Section 7.4 |
| F5 | **A customer must never receive the same reminder twice, provably** | Sections 7, 11 |
| F6 | Handle corner cases and failure cases | Sections 8, 9 |

### 1.2 Functional (added, with reasoning)

The brief invites "any optional reasonable behaviour". Each of these four additions
closes a correctness gap:

| # | Addition | Why it isn't optional in practice |
|---|---|---|
| F7 | Idempotent creation via `Idempotency-Key` header | Without it, a client retry on a timeout creates a second appointment, and the customer gets two 24h reminders. That violates F5 without a single duplicate send. |
| F8 | Cancel (`DELETE`) | An unsent reminder for a cancelled appointment is a wrong message to a real customer. |
| F9 | Reschedule (`PATCH`) | The #1 real-world event for service appointments. It is also the reason queue-based delay is rejected (section 12.2). |
| F10 | `GET /appointments/{id}/reminders` | This is the proof artifact, and the demo video shows it. |

### 1.3 Non-functional

| Property | Target | Notes |
|---|---|---|
| Reminder timeliness | p99 sent within 5 min of nominal due time | "24 hours before" is not a real-time guarantee; nobody notices 90 seconds. |
| Duplicate rate | 0, enforced at storage layer | Section 7.3 |
| Miss rate | 0 for reminders whose window is still useful | Section 8.3 defines "useful" |
| API latency | p99 < 150 ms for `POST /appointments` | Single local transaction |
| Availability | 99.9% API; reminder dispatch tolerates hours of downtime | Late is recoverable, lost is not |
| Durability | No acknowledged appointment is lost | Synchronous replica commit |

### 1.4 Explicitly out of scope

Slot/bay capacity management, technician assignment, real SMS/email delivery,
customer-facing auth, billing, DMS integration. They are listed here to show they were
considered and cut on purpose.

---

## 2. Load estimation

Assumptions, stated so they can be challenged:
- 500 dealerships, US, spread across 4 timezones.
- Bookings arrive during business hours, so a day has ~10 effective hours, not 24.
- Appointments are booked into discrete slots (`:00` / `:30`), ~16 usable slots/day.
- 2 reminders per appointment.

| Metric | 1× (brief) | 10× (our target) | Comment |
|---|---|---|---|
| Appointments/day | 50,000 | 500,000 | |
| Avg write TPS | 0.58/s | 5.8/s | flat over 24h |
| Business-hours write TPS | 1.4/s | 14/s | realistic average |
| Peak write TPS (5× burst) | 7/s | 70/s | Monday 9am |
| Reminder sends/day | 100,000 | 1,000,000 | |
| Avg send TPS | 1.2/s | 11.6/s | |
| Slot-boundary burst | ~3,100 | ~31,000 in one second | the real constraint |
| New rows/year | 55M | 547M | 182M appts + 365M reminders |
| Storage/year (+ indexes) | ~20 GB | ~200 GB | ~650 B per appointment incl. its 2 reminders |

70 writes/sec is laptop scale. A single `db.r6g.xlarge` Postgres does 3,000+ write TPS
without tuning, so we are at ~2% of one node. Sharding, Kafka, or a microservice split
would make the system worse here: each adds failure modes and on-call surface and
gains no capacity.

Two numbers do drive the design:

- 31,000 reminders due at once. Appointments cluster on the hour, so the 24h-prior
  reminders cluster on the hour too. Batched claiming (section 6.2) and optional deterministic
  jitter (section 6.4) handle this; the burst takes ~1 to 1.5 minutes to drain, against a
  5-minute SLO.
- 365M reminder rows/year. Range partitioning on `due_at` plus 13-month retention
  (section 10.2) keeps the working set at the next 48 hours, however much history builds up.

---

## 3. High-level architecture

```
   Dealer DMS / Booking UI
             │  REST + Idempotency-Key
             ▼
   ┌─────────────────────┐
   │   API nodes  (×3)   │   stateless, behind LB
   │   Spring Boot       │   profile: api
   └──────────┬──────────┘
              │  ONE transaction:
              │  INSERT appointment + INSERT 2 reminders
              ▼
   ┌──────────────────────────────────┐
   │        PostgreSQL 16             │
   │  ┌────────────────────────────┐  │      primary
   │  │ appointment                │  │        │ sync commit
   │  │ reminder        ◄── the    │  │        ▼
   │  │ reminder_attempt   queue   │  │   standby (failover)
   │  │ dealership                 │  │        │ async
   │  └────────────────────────────┘  │        ▼
   └──────────┬───────────────────────┘   read replica (GETs, reports)
              │  SELECT … FOR UPDATE SKIP LOCKED
              │  poll 1s · batch 200
              ▼
   ┌─────────────────────┐
   │ Dispatcher pool ×3  │   same JAR, profile: worker
   │ claim → send → ack  │   scaled independently of API
   └──────────┬──────────┘
              │  send(payload, idempotencyKey)
              ▼
   ┌─────────────────────────────────────┐
   │ NotificationSender  (interface)     │
   │  · LoggingNotificationSender ← stub │
   │  · TwilioSender / SesSender (later) │
   └─────────────────────────────────────┘
```

API and worker are one deployable with two profiles: the same JAR with
`app.worker.enabled=true/false`. There is no separate build and no shared-library
drift, and the roles still scale independently. Splitting into two services would
gain nothing here.

Workers poll the database instead of receiving events because the write that creates
a reminder and the record that proves it was sent are the same row. A broker would
hold a second copy of that state, and any divergence between the two copies is a
duplicate or a missed reminder, which are the two bugs this design is meant to eliminate.

---

## 4. Data model

```sql
CREATE TABLE dealership (
    id                BIGSERIAL PRIMARY KEY,
    external_id       VARCHAR(64)  NOT NULL UNIQUE,
    name              VARCHAR(200) NOT NULL,
    timezone          VARCHAR(64)  NOT NULL,   -- IANA: 'America/Chicago'
    quiet_hours_start TIME,                    -- local; NULL = no quiet hours
    quiet_hours_end   TIME,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE appointment (
    id              BIGSERIAL PRIMARY KEY,
    public_id       UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    dealership_id   BIGINT       NOT NULL REFERENCES dealership(id),
    customer_name   VARCHAR(200) NOT NULL,
    customer_phone  VARCHAR(20),              -- E.164
    customer_email  VARCHAR(320),
    channel         VARCHAR(16)  NOT NULL,    -- SMS | EMAIL
    vehicle_vin     VARCHAR(17),              -- optional
    vehicle_description VARCHAR(200) NOT NULL,
    service_type    VARCHAR(64)  NOT NULL,    -- dealer's own codes: free text
    scheduled_at    TIMESTAMPTZ  NOT NULL,    -- the instant, always UTC-normalised
    local_tz        VARCHAR(64)  NOT NULL,    -- snapshot of dealership tz at booking
    status          VARCHAR(16)  NOT NULL,    -- BOOKED|CANCELLED|COMPLETED|NO_SHOW
    idempotency_key VARCHAR(128) NOT NULL,
    version         INT          NOT NULL DEFAULT 0,   -- optimistic lock
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_appt_idem UNIQUE (dealership_id, idempotency_key),
    -- Implies "at least one contact present", so no separate check for that.
    CONSTRAINT ck_channel   CHECK (
        (channel = 'SMS'   AND customer_phone IS NOT NULL) OR
        (channel = 'EMAIL' AND customer_email IS NOT NULL)),
    -- CHECK, not a Postgres ENUM: values can change later without rebuilding a type.
    CONSTRAINT ck_status    CHECK (status IN ('BOOKED','CANCELLED','COMPLETED','NO_SHOW'))
);
-- idx_appt_dealer_time (dealership_id, scheduled_at) ships with the
-- dealership listing endpoint, not before: no query uses it until then.

CREATE TABLE reminder (
    id               BIGSERIAL PRIMARY KEY,
    appointment_id   BIGINT      NOT NULL REFERENCES appointment(id) ON DELETE CASCADE,
    reminder_type    VARCHAR(16) NOT NULL,    -- T24H | T2H
    due_at           TIMESTAMPTZ NOT NULL,
    status           VARCHAR(16) NOT NULL,    -- see section 7.2
    idempotency_key  UUID        NOT NULL,    -- deterministic, see section 7.3
    appointment_version INT      NOT NULL DEFAULT 0,  -- which schedule this pair is for, section 8.4
    attempt_count    SMALLINT    NOT NULL DEFAULT 0,
    claimed_by       VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    sent_at          TIMESTAMPTZ,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ◄── THIS LINE IS REQUIREMENT F5 ──────────────────────────────
    CONSTRAINT uq_reminder UNIQUE (appointment_id, reminder_type, appointment_version),
    CONSTRAINT ck_reminder_type   CHECK (reminder_type IN ('T24H','T2H')),
    -- A misspelt status would fall outside the partial indexes and never send.
    CONSTRAINT ck_reminder_status CHECK (status IN
        ('PENDING','CLAIMED','SENT','DEAD','SKIPPED_LATE','CANCELLED'))
);

-- Partial indexes: only rows in a working state are indexed, so the
-- index stays small even as the table grows to hundreds of millions.
CREATE INDEX idx_reminder_due   ON reminder (due_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_reminder_lease ON reminder (lease_expires_at)
    WHERE status = 'CLAIMED';

-- Append-only audit trail. This is what makes F5 *provable* rather than
-- merely true: it shows the attempts that did NOT result in a send.
CREATE TABLE reminder_attempt (
    id           BIGSERIAL PRIMARY KEY,
    reminder_id  BIGINT       NOT NULL REFERENCES reminder(id) ON DELETE CASCADE,
    attempt_no   SMALLINT     NOT NULL,
    worker_id    VARCHAR(64)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    outcome      VARCHAR(16),               -- NULL while in flight
    provider_ref VARCHAR(128),
    error        TEXT,

    CONSTRAINT ck_attempt_outcome CHECK (outcome IN ('OK','RETRYABLE','PERMANENT','ABANDONED'))
);
CREATE INDEX idx_attempt_reminder ON reminder_attempt (reminder_id);
```

### 4.1 Why `TIMESTAMPTZ` *and* a separate `local_tz`

The two columns answer different questions:

- `scheduled_at` (an instant) answers "is it 24 hours before yet?" That is
  elapsed-time arithmetic and must not involve a calendar.
- `local_tz` answers "what time does the customer think this is?" It is needed to
  render "Tue 2:00 PM" in the message body and to evaluate quiet hours.

Storing only wall-clock time breaks across DST. Storing only UTC produces messages
that say the wrong hour. The tz is snapshotted onto the appointment instead of
joined from `dealership`, so changing a dealership's timezone tomorrow doesn't
silently rewrite the meaning of appointments booked yesterday.

### 4.2 Why reminder rows are materialised at booking time

The alternative is computing due reminders on the fly
(`WHERE scheduled_at - interval '24 hours' <= now()`). That was rejected because:

- There is nowhere to record that a send happened, and recording that is the whole assignment.
- There is no place to keep `attempt_count`, `last_error`, or the lease.
- The scan covers all appointments instead of a small partial index.

Materialising costs two extra rows per appointment, and the whole correctness argument
rests on those rows. They are inserted in the same transaction as the appointment, so an
appointment can never exist without its reminders and no reconciliation job is needed
to catch a gap.

---

## 5. API

All endpoints under `/v1`. Errors use RFC 7807 `application/problem+json`.

### `POST /v1/appointments`

```http
POST /v1/appointments
Idempotency-Key: 7f3a9c1e-4b2d-4e8a-9f11-2c6d8e0a5b37     # required
Content-Type: application/json

{
  "dealershipId": "DLR-0042",
  "customer": { "name": "Ana Marquez",
                "phone": "+14155550137",
                "channel": "SMS" },
  "vehicle":  { "vin": "1HGCM82633A004352", "description": "2019 Civic" },
  "serviceType": "OIL_CHANGE",
  "scheduledAt": "2026-09-24T14:00:00-05:00"
}
```

```http
201 Created
Location: /v1/appointments/3f2b...

{
  "id": "3f2b8c44-...",
  "status": "BOOKED",
  "scheduledAt": "2026-09-24T19:00:00Z",
  "localTime": "2026-09-24T14:00:00-05:00"
}
```

Every request is validated at the trust boundary:

| Field | Rule | On failure |
|---|---|---|
| `Idempotency-Key` | present, non-blank, ≤128 chars | `400` |
| required fields, lengths, VIN | present, fit their columns, no U+0000 (Postgres can't store it) | `400` |
| `phone` | E.164 | `400` |
| `channel` | matching contact field present (DB `CHECK` backs this) | `400` |
| `scheduledAt` | parseable with offset | `400` |
| `scheduledAt` | `> now()`, `≤ now() + 365d` | `422` |
| `scheduledAt` | offset is one the dealership's zone shows at that local time (rejects DST-gap times, a stale offset, and `Z`) | `422` |
| `dealershipId` | exists | `422` |
| body | ≤ 8 KB, by `Content-Length` (a chunked body is not checked; cap it at the ingress) | `413` |

`400` means the request itself is malformed: the client can tell from the request
alone what to fix. `422` means it is well formed but the answer depends on our state
or clock: an unknown dealership, a time outside the booking window.

An idempotent replay (same key, same dealership) gets `200 OK` with the original
appointment and writes no new rows. The replay lookup runs before the time rules, so a
retry that arrives after the booking window has moved on still gets the original answer.
The same key with a different payload gets `409 Conflict`. That is a real client bug,
and silently returning the old object would hide it.

### Other endpoints

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/appointments/{id}` | served from read replica |
| `PATCH` | `/v1/appointments/{id}` | reschedule; `If-Match: <version>` → `409` on conflict |
| `DELETE` | `/v1/appointments/{id}` | cancel (soft; sets `status=CANCELLED`) |
| `GET` | `/v1/appointments/{id}/reminders` | the proof endpoint: full state + attempt history |
| `GET` | `/v1/dealerships/{id}/appointments?from&to` | paginated, keyset on `(scheduled_at, id)` |
| `GET` | `/actuator/health/{liveness,readiness}` | k8s probes |
| `GET` | `/actuator/prometheus` | Section 10.1 metrics |

---

## 6. Scheduling: how a reminder becomes due

### 6.1 Computing `due_at`

```java
Instant dueAt = appointment.scheduledAt().minus(type.lead());   // 24h or 2h
```

This is plain elapsed-time subtraction on an instant, so it is DST-correct by
construction: "24 hours before" means 24 hours of real time, whether or not a clock
changed in between. There is no `ZonedDateTime` arithmetic and so no calendar edge
cases. The DST case that does need handling is on the input side (section 8.1).

### 6.2 The claim loop

Every worker node runs this on `@Scheduled(fixedDelay = 1000)`. **There is no ShedLock
and no leader election.** `SKIP LOCKED` lets every node run the query concurrently
without two nodes claiming the same row.

```sql
UPDATE reminder
   SET status           = 'CLAIMED',
       claimed_by       = :workerId,
       lease_expires_at = now() + interval '60 seconds',
       attempt_count    = attempt_count + 1
 WHERE id IN (
       SELECT id
         FROM reminder
        WHERE status = 'PENDING'
          AND due_at <= now()            -- ◄ DB clock, never the app's (section 9, FM-7)
        ORDER BY due_at
          FOR UPDATE SKIP LOCKED
        LIMIT 200
 )
RETURNING *;
```

- `SKIP LOCKED`: concurrent workers step over each other's rows instead of blocking.
  Throughput scales linearly with worker count, and there is no contention hot spot.
- `ORDER BY due_at`: oldest first. That is fair across dealerships (arrival order) and
  is also the right priority, because the most-overdue reminder is the most at risk.
- `LIMIT 200`: bounds transaction size and keeps lock duration in the low milliseconds.
- The partial index `WHERE status='PENDING'` means this is a left-edge range scan,
  independent of table size.

### 6.3 Lease reclamation

A separate sweeper (`@Scheduled(fixedDelay = 30s)`) runs on every worker, like the claim loop:

```sql
UPDATE reminder
   SET status = 'PENDING', claimed_by = NULL, lease_expires_at = NULL
 WHERE id IN (
       SELECT id
         FROM reminder
        WHERE status = 'CLAIMED'
          AND lease_expires_at < now()
          FOR UPDATE SKIP LOCKED
 );
```

The sweeper has no ShedLock either. The sweep is idempotent: a row one worker has already
released no longer matches `status = 'CLAIMED'`, and `SKIP LOCKED` means two concurrent
sweeps step over each other instead of waiting or deadlocking. A lock would only save
a couple of empty index scans every 30s, which isn't worth a dependency and a lock
table. ShedLock is for jobs that would do harm if they ran twice (section 10.2).

A worker that is killed mid-flight loses its claim after 60 seconds, and the reminder
is retried. This makes the system tolerant of pod evictions. The same mechanism
creates the only real duplicate risk, which section 7.3 closes.

### 6.4 The slot-boundary burst

31,000 reminders become due at `09:00:00`. Each worker sends a claimed batch
concurrently on virtual threads (section 7.4), so a batch costs its slowest send, not the sum
of 200. Drain rate, at measured provider latency (section 7.4):

```
one batch  =  slowest of 200 sends (~0.2s SMS, ~0.5s email) + 1s poll delay  ≈  1.2–1.5s
200 / 1.2–1.5s × 3 workers  ≈  400–500 reminders/sec
31,000 / 400–500            ≈  60–80 seconds
```

That is inside the 5-minute SLO with about 4× headroom. Sent one at a time, the same
batch takes 23s (SMS) to 35s (email), and the burst would take 20 to 30 minutes. The
stub's realistic latency is what showed this.

If the downstream provider is the bottleneck instead of the dispatcher (Twilio rate
limits are real), add deterministic jitter at insert time:

```java
// spread a slot's reminders over 2 minutes, stable across recomputation
long jitterSec = Math.floorMod(appointment.publicId().hashCode(), 120);
Instant dueAt  = scheduledAt.minus(type.lead()).minusSeconds(jitterSec);
```

The jitter is deterministic, not random, so a reschedule recomputes the identical
value. It shifts the reminder earlier, never later, so the reminder never arrives
under its nominal lead time.

Sending a whole batch at once can also exceed what the provider accepts. Twilio
answers `429` when an account has too many concurrent requests (the limit isn't
published), and SES rejects anything above the account's send rate, about
14 emails/sec for a new production account. Each worker caps its in-flight sends per
channel with a `Semaphore` sized from config (`app.sender.max-in-flight.*`), and a
rejected send comes back `RETRYABLE`, so jittered backoff spreads the retries out. The
cap is per worker, so it is set to the account's limit divided by the number of
workers. SES's limit is a rate, which a concurrency cap only approximates
(rate ≈ cap / latency); a rate limiter for email is the upgrade if SES throttles.

A send renews its lease when it gets a slot, so the lease times the send, not the wait.
One that waits out its whole first lease finds the row handed on and drops it instead
of sending it twice; keep 200 / cap × the slowest send under 60s to avoid that churn.
At SES's default quota the provider bounds the burst, not the dispatcher:
31,000 emails at 14/sec is about 37 minutes, so the 10× target needs a raised quota.

---

## 7. Exactly-once: the core of the assignment

> "A customer must never receive the same reminder twice. Should be provable."

### 7.1 What is achievable

Exactly-once delivery over a network is impossible; this is the Two Generals problem.
A send can succeed while the acknowledgement is lost, and the sender cannot distinguish
that from a send that failed. A design that claims exactly-once delivery is hand-waving.

This design guarantees what can be achieved:

> For every `(appointment, reminder_type, appointment version)`, the system performs **at most one
> successful, committed send**, and any repeated attempt carries an identical
> idempotency key so the downstream provider collapses it.

That is "effectively once", the strongest claim that holds. A database constraint
enforces it, so it does not depend on code discipline.

### 7.2 Reminder lifecycle

```
                    appointment created (one TX)
                              │
              ┌───────────────┴────────────────┐
     due_at already past?                  due_at future
              │                                 │
       SKIPPED_LATE                          PENDING ◄─────────────┐
     (booked < 24h out —                        │                  │
      never fires, section 8.2)          claim (SKIP LOCKED)       lease expiry (section 6.3)
                                                │              retryable backoff
                                                ▼                  │
                                            CLAIMED ───────────────┘
                                                │
                       ┌────────────────────────┼─────────────────────┐
                  provider OK            permanent error       attempts > 5
                       ▼                        ▼                     ▼
                     SENT                     DEAD                  DEAD
                  (terminal)                (alert)               (alert)

   appointment CANCELLED    → PENDING/CLAIMED ⇒ CANCELLED   (SENT untouched)
   appointment RESCHEDULED  → PENDING/CLAIMED ⇒ CANCELLED, new pair for the new time (SENT untouched)
   now() past usefulness    → SKIPPED_LATE                   (section 8.3)
```

`SENT`, `DEAD`, `SKIPPED_LATE` and `CANCELLED` are terminal, with one exception: a
`CANCELLED` row whose message was already with the provider settles `SENT`, because it
was delivered (section 8.4). Nothing transitions *out* of `SENT`; that is the invariant the
whole design protects.

### 7.3 The three layers

Layer 1 is the constraint, which prevents duplicates in storage.

```sql
CONSTRAINT uq_reminder UNIQUE (appointment_id, reminder_type, appointment_version)
```

There is exactly one row per (appointment, type, appointment version), so `SENT` is a
single bit per reminder, not a countable event. The database cannot hold two "sent"
records for the same reminder. Any code path that tried to write a second one would
get a constraint violation.

> Scope of "the same reminder": uniqueness is per appointment, not per customer.
> A customer who brings two vehicles in on the same day has two appointments and
> correctly receives two T24H reminders. Deduplicating per customer per day would
> suppress a legitimate reminder for the second vehicle.

Layer 2 is the lease, which prevents two workers from sending concurrently.

`SKIP LOCKED`, the `CLAIMED` status, and `lease_expires_at` together mean at most one
worker holds a given reminder at a time. The same reminder can still be sent twice in
two cases: the worker crashes between send and settle, or a send outlives its 60s lease and another worker
re-claims the swept row while the first call is still open. Every sender therefore
needs a timeout well under the lease.

Layer 3 is the idempotency key, which collapses the crash-retry duplicate. It covers
the window Layer 2 leaves open:

```
worker claims ──► sender.send() succeeds ──► 💥 worker dies before commit
                                              lease expires
                                              another worker re-claims
                                              sends AGAIN  ← duplicate
```

The second send is byte-identical to the first, including its key:

```java
// Name-based UUID (v3, stdlib) — a pure function of (appointment, type, version). Same input, same UUID, forever.
UUID key = UUID.nameUUIDFromBytes(
        (appointment.publicId() + ":" + reminderType + ":" + appointmentVersion).getBytes(UTF_8));
```

The key is computed once, stored on the row, and never regenerated. It is passed to
`NotificationSender.send(payload, key)`. A provider that deduplicates on a
caller-supplied key drops the retry. Check each provider before relying on this: SES
`SendEmail` accepts no idempotency key (`MessageDeduplicationId` is an SQS FIFO
parameter). With a provider like that, a crash between send and settle reaches the
customer twice. We measured how many rows that affects by stopping a stub-backed worker
while a batch was in flight:

| Event mid-batch | Re-sent | Why |
|---|---|---|
| `SIGTERM` (deploy, scale-in) | 0 of 4,000 | Spring waits for the running poll, so the batch settles before exit |
| `kill -9` (OOM, eviction) | 50 of 4,000 | Rows already sent but still queued for a pool connection to settle |

Both runs left one `SENT` row per reminder. The repeated sends carried the same key, so
they show up in the logs even when the provider can't drop them.

If `SENT` were marked before sending, a crash before the send would lose the reminder
permanently, and the row would record a send that never happened. The choice
between "might duplicate" and "might lose" is the classic at-least-once / at-most-once
fork. The idempotency key lets the design take at-least-once (never lose) and still
satisfy F5.

### 7.4 The send path, precisely

```java
// TX 1 — claim, and read what the sends need.  Short, bounded, no network I/O inside.
List<Reminder> batch = reminderRepo.claimDue(workerId, 200);

// Every send in the batch starts at once, one virtual thread each (section 6.4).
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (Reminder r : batch) executor.execute(() -> {
        Long attemptId = attemptRepo.open(r.id(), r.attemptCount(), workerId);  // own TX

        // ── OUTSIDE any transaction.  See the note below. ──
        SendResult result = sender.send(payloadFor(r), r.idempotencyKey());

        // TX 2 — settle, only while this worker still holds the claim.
        switch (result.outcome()) {
            case OK        -> reminderRepo.markSent(r.id(), workerId);
            case RETRYABLE -> reminderRepo.scheduleRetry(r.id(), workerId, backoff(r.attemptCount()));
            case PERMANENT -> reminderRepo.markDead(r.id(), workerId, result.error());
        }
        attemptRepo.close(attemptId, result);
    });
}
```

> **The network call is deliberately outside the transaction.** Holding a DB
> connection open across a provider call that can hang for 30 seconds would exhaust a
> HikariCP pool of 20, and one slow vendor would take down the whole service. During
> the call, the lease protects the row, not a transaction.

Every settle is guarded by `WHERE status = 'CLAIMED' AND claimed_by = :workerId`. A
worker whose call outlived its 60s lease has lost the row to the sweeper (section 6.3), so its
late write changes nothing. Without the guard, a late `RETRYABLE` could move a row
another worker had already marked `SENT` back to `PENDING`, and it would go out again.

The stub sender required by the brief:

```java
@Component
class LoggingNotificationSender implements NotificationSender {
    public SendResult send(NotificationPayload p, UUID idempotencyKey) {
        Thread.sleep(latency(p.channel(), ThreadLocalRandom.current()));
        log.info("NOTIFICATION channel={} to={} type={} apptId={} idempotencyKey={} body={}",
                 p.channel(), mask(p.recipient()), p.reminderType(),
                 p.appointmentId(), idempotencyKey, p.body());
        return SendResult.ok("log-" + idempotencyKey);
    }
}
```

Recipients are masked in logs (`+1415•••0137`, `a•••@example.com`) because PII doesn't
belong in an aggregated log store.

The stub sleeps for as long as a real provider takes to accept the request, so
dispatcher timing in tests and the demo matches production. The delay is drawn from
a log-normal fitted to measured acceptance times ([Knock benchmarks](https://knock.app/sms-api-benchmarks/twilio),
June to September 2026): SMS uses Twilio's p50 114ms / p99 176ms, and email uses
[Amazon SES](https://knock.app/email-api-benchmarks/aws-ses)'s p50 162ms / p99 426ms.
There is no switch for choosing a sender yet; add `@ConditionalOnProperty` when a
second implementation exists.

### 7.5 Retry policy

| Outcome | Examples | Action |
|---|---|---|
| `OK` | 2xx from provider | → `SENT`, terminal |
| `RETRYABLE` | timeout, 429, 5xx, connection reset | → `PENDING`, `due_at = now() + min(30s·2ⁿ, 15min)`, ±20% jitter |
| `PERMANENT` | invalid number, unsubscribed, 400 | → `DEAD` immediately. Retrying a malformed phone number 5 times is 5 guaranteed failures. |
| 6th send dies mid-flight | worker died, or the send outlived its lease, every time | → `DEAD` + page. Caps the poison-row blast radius (section 9, FM-9). |

The attempt cap applies only when a lease expires. A row whose sends keep dying won't
succeed on a seventh try, but a provider outage ends. If `RETRYABLE` counted toward the
cap, every reminder due in the first ~15 minutes of a longer outage would be dropped for
good (FM-5). So the cap counts sends, not claims: `reminder_attempt` rows left open by a
dead worker, or closed `ABANDONED` because the worker lost the row before it could
settle. A `RETRYABLE` answer, or a claim whose lease ran out while it waited for a send
slot, doesn't count. Useful lead time bounds retries instead (section 8.3).

---

## 8. Corner cases

### 8.1 Time and DST

| Case | Behaviour |
|---|---|
| "24h before" spanning a DST change | Correct by construction: subtraction on an `Instant` is elapsed time, unaffected by clock changes (section 6.1). |
| Client sends `2026-03-08T02:30:00-06:00` (a local time that doesn't exist on spring-forward) | Reject `422`. The offset is explicit in the payload, so this is detectable at parse time; guessing between 01:30 and 03:30 CST is worse than asking. |
| Ambiguous local time on fall-back (1:30 AM occurs twice) | The required explicit offset disambiguates it. This is why the offset is mandatory instead of accepting a bare local time + tz. |
| Dealership changes its timezone | Existing appointments keep their snapshotted `local_tz` (section 4.1). Instants never move. |
| Appointment crossing midnight/year boundary | No special case. Instants are unaffected by calendar boundaries. |

### 8.2 Short-notice bookings

For a booking made 3 hours before the appointment, the T24H reminder's `due_at` is
21 hours in the past, and it must not fire.

```java
Instant dueAt = scheduledAt.minus(type.lead());
ReminderStatus initial = dueAt.isBefore(now) ? SKIPPED_LATE : PENDING;   // now = Postgres now(), FM-7
```

The reminder is inserted as `SKIPPED_LATE` at creation time instead of being filtered
at dispatch time. The row still exists, so `GET /reminders` can accurately show
"T24H: skipped, booked inside the window" instead of a confusing gap. The same logic
applies to a booking made 30 minutes out: both reminders are `SKIPPED_LATE`.

> Open question (section 14, Q4): should a short-notice booking send an immediate
> confirmation instead? That is reasonable, but it is a different notification type
> and the brief doesn't ask for it. Not implemented; flagged.

### 8.3 Late dispatch and useful lead time

If workers were down for 3 hours, overdue reminders are waiting, and the system has to
decide whether they should still go out.

A fixed grace window on `due_at` gets this wrong. The check has to look at the
appointment's time instead of the reminder's:

```java
boolean stillUseful = now.isBefore(appointment.scheduledAt().minus(type.minUsefulLead()));
// T24H → minUsefulLead = 2h   (below that it collides with the T2H reminder)
// T2H  → minUsefulLead = 15m  (below that the customer is already driving over)
```

A reminder that is no longer useful becomes `SKIPPED_LATE`: no send, a metric
increment, and an alert. Sending "reminder: your appointment is in 2 hours" 20 minutes
after the appointment started is worse than sending nothing, because it confuses the
customer and generates a support call.

### 8.4 Cancel and reschedule

| Event | Effect |
|---|---|
| Cancel | `PENDING`/`CLAIMED` → `CANCELLED`. Already-`SENT` rows are untouched, because a sent message cannot be unsent. |
| Cancel racing with dispatch | The claim transaction has committed before the send, so nothing blocks the cancel. It marks the `CLAIMED` row `CANCELLED` at once. If the worker hasn't opened its attempt yet, the send is stopped. If the message is already with the provider, it arrives, and the settle turns the row into `SENT` so the record matches what the customer got. Accepted: a stale reminder for a just-cancelled appointment is a minor annoyance, and coordinating a transaction with an in-flight external call is not achievable. |
| Reschedule | Unsent reminders (`PENDING`/`CLAIMED`) → `CANCELLED`, and a fresh pair is written for the new time under the appointment's new version. It goes through the same constructor as booking, so section 8.2's past-check re-applies: moving an appointment to 1 hour from now writes both as `SKIPPED_LATE`. |
| Reschedule after T24H already `SENT` | The `SENT` row stays `SENT`; the customer also gets a T24H for the new time. That is new information, not a repeat; the client confirmed this behaviour (section 14, Q2). |
| Reschedule to the same time | No-op, `200`. Nothing new to tell the customer, so no new pair. |
| Reschedule after cancellation | `409`, because a cancelled appointment is terminal. |

> This still satisfies F5, because "never twice" holds per appointment version: the key
> is `(appointment_id, reminder_type, appointment_version)`, and the version is in the
> idempotency key too, so a provider that dedupes doesn't swallow the new pair. A
> reschedule racing an in-flight send is the same accepted race as cancel.

### 8.5 Duplicate appointments

Two `POST`s for the same customer and slot with different idempotency keys create two
appointments and four reminders, and the customer is annoyed. That is a duplicate
booking, not a duplicate send.

The mitigation is a soft warning instead of a hard constraint:
`GET /appointments?dealershipId&phone&window=±2h` returns existing bookings so the
caller can confirm. A unique constraint on `(dealership, phone, scheduled_at)` would be
wrong, because a family sharing one phone number legitimately books two vehicles into
adjacent slots.

---

## 9. Failure modes

| # | Failure | Detection | Response | Residual risk |
|---|---|---|---|---|
| FM-1 | Provider times out | `SendResult.RETRYABLE` | Backoff retry, same idempotency key | Provider may have delivered; key dedupes it |
| FM-2 | Worker killed mid-send (OOM, eviction) | `lease_expires_at` passes | Sweeper → `PENDING` → re-claim, 60 to 90s later | Rows sent but not yet settled go out again with the same key; collapsed only where the provider dedupes (section 7.3). A graceful deploy re-sends none. |
| FM-3 | DB failover mid-transaction | Connection error | TX rolls back; nothing committed; row stays `PENDING` | None; atomicity holds |
| FM-4 | DB unreachable for N minutes | Health check red | API returns `503` (fail fast, don't queue in memory); dispatch pauses and catches up | Reminders late, not lost. Section 8.3 suppresses the ones that went stale. |
| FM-5 | Provider hard-down for hours | Error-rate alert | Backoff caps at 15 min; circuit breaker trips to stop hammering | Reminders inside their useful window still go out on recovery |
| FM-6 | Duplicate `POST` (client retry) | `uq_appt_idem` violation | Return the original `200` | None |
| FM-7 | Clock skew between app nodes | n/a | **All time comparisons use DB `now()`.** App clocks are never authoritative for due-ness. | None. A node 4 minutes fast would otherwise fire every reminder early. |
| FM-8 | Slot-boundary thundering herd | `reminder_lag` spike | Batched claim (section 6.2), concurrent sends (section 7.4) + optional jitter (section 6.4) | ~60 to 80s drain vs 5-min SLO |
| FM-9 | Poison row (crashes the worker every time) | `attempt_count > 5` | → `DEAD` + page | One row parked, pipeline unblocked |
| FM-10 | Connection pool exhausted | HikariCP timeout metric | Network I/O is outside transactions (section 7.4); pool sized in section 10.3 | n/a |
| FM-11 | Noisy neighbour: one dealership bulk-loads 100k appointments | Per-tenant claim-share metric | `ORDER BY due_at` is naturally FIFO-fair; add per-tenant token bucket if it bites | Monitored; not pre-solved |
| FM-12 | Someone reruns a migration / restores a backup | `uq_reminder` | Duplicate inserts rejected by the constraint | None |

---

## 10. Operations

### 10.1 The one metric that matters

```
reminder_lag_seconds  =  now() − min(due_at)  WHERE status = 'PENDING' AND due_at <= now()
```

This is the age of the oldest reminder that should already have gone out. That one
number catches a crashed dispatcher, a wedged provider, a slow query, and a lock
pile-up. Alert at `> 300s` (the SLO).

Supporting metrics: `reminder_send_seconds{type,channel,outcome}`, a histogram whose
`_count` is the sends counter, `reminders_dead_total{reason}` (`crashes` pages, FM-9;
`permanent` is a bad number), and `reminders_skipped_late_total` (should be near-zero;
a spike means we lost time). `claim_batch_size` and `appointments_created_total{dealership}`
are not built yet.

### 10.2 Nightly jobs (ShedLock-guarded singletons)

- Invariant check (the F5 receipt), run in prod and in CI:
  ```sql
  SELECT appointment_id, reminder_type, appointment_version, count(*)
    FROM reminder GROUP BY 1,2,3 HAVING count(*) > 1;          -- must be 0 rows
  ```
- Orphan check: finds `BOOKED` appointments missing a reminder row. That should be
  impossible given section 4.2's single transaction, and verifying it is cheap.
- Partition roll: creates next month's `reminder` partition, then runs `DETACH` and
  archives anything past 13 months. `DETACH` is instant, while `DELETE FROM` on 30M
  rows is a vacuum storm.

### 10.3 Sizing at 10×

| Resource | Size | Reasoning |
|---|---|---|
| API nodes | 3 × 2 vCPU | 70 peak TPS is ~2% utilisation; 3 is for AZ redundancy, not load |
| Worker nodes | 3 × 2 vCPU, one poll loop each, sends on virtual threads | ~400 to 500 reminders/sec capacity vs 11.6/sec average |
| Postgres | 1 primary (4 vCPU / 32 GB) + sync standby + async read replica | Working set = next 48h of reminders ≈ 3 GB, fits in RAM |
| Connection pool | API 15 each, worker 10 each = 75 total | Under a 100-connection cap. Add PgBouncer in transaction mode before adding nodes, because Postgres degrades badly past a few hundred connections |
| Storage | 300 GB provisioned | ~200 GB/yr with 13-month retention |

### 10.4 When to change the architecture

The trigger points are fixed in advance so the decision isn't made in a panic:

| Signal | Threshold | Action |
|---|---|---|
| `reminder_lag` p99 | > 60s sustained | Add worker replicas (horizontal, no code change) |
| Claim query p99 | > 50 ms | Range-partition `reminder` by `due_at`, monthly |
| `reminder` row count | > 100M | Partitioning becomes mandatory; enable archival |
| Write TPS | > 1,000 | Move all `GET`s to the read replica |
| Write TPS | > 3,000 (≈ 250M appts/day, 5,000× the brief) | Shard by `dealership_id` at this point and not before. Shards are tenant-aligned, so there are no cross-shard transactions |
| Provider rate-limit errors | > 0.1% | Per-tenant token bucket + dedicated outbound worker pool |

By design, everything up to 1,000 TPS is a config change.

---

## 11. Proving "never twice"

The brief says the guarantee must be provable. There are four independent artifacts,
strongest first.

1. The constraint. `UNIQUE (appointment_id, reminder_type, appointment_version)` makes a
duplicate unrepresentable. This is the proof; the rest is evidence that the
surrounding code respects it.

2. The concurrency test. This is the test that would catch a regression:

```java
@Test
void tenWorkersRacingOneReminder_sendExactlyOnce() throws Exception {
    long id = dueReminder();
    var startingGun = new CyclicBarrier(10);   // all 10 hit the claim query together
    var workers = new ArrayList<Callable<Void>>();
    for (int i = 0; i < 10; i++) {
        var worker = new Dispatcher(...);
        workers.add(() -> { startingGun.await(); worker.poll(); return null; });
    }
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var finished : pool.invokeAll(workers)) finished.get();
    }

    assertThat(sent).containsExactly(keyOf(id));
    assertThat(statusAndAttempts(id)).isEqualTo("SENT:1");
}
```

Without `FOR UPDATE`, all ten workers claim the row (`SENT:10`), yet the send count
can still read 1. Each claim overwrites `claimed_by` and `openAttempt` checks it, so
usually only the last claimer sends. That is why the test also asserts the claim count.
Dropping only `SKIP LOCKED` keeps this test green, because workers wait for the row
instead of double-claiming it; `rowsLockedByOneWorker_areSkippedByAnotherInsteadOfWaitedOn`
catches that case.

The test runs against real PostgreSQL via Testcontainers. H2 does not implement
`SKIP LOCKED`, so on an in-memory DB this test would pass while the production
behaviour went unverified. It is the most important test in the repo.

3. The crash test. The sender records the call, then throws before `markSent`. The
test advances the lease clock, re-runs the dispatcher, and asserts two attempts in
`reminder_attempt`, two provider calls carrying the identical idempotency key, and
one `SENT` row. It demonstrates the at-least-once → effectively-once collapse in
running code.

4. The randomised workload. 10,000 appointments (20,000 reminders) go through ten
workers and a provider that times out on 10% of calls and kills the worker mid-send on
2%. The test asserts that no reminder is left unfinished, that each key the provider
accepted belongs to exactly one `SENT` row, and that every call carried a stored key.
It ends with the section 10.2 duplicate query. That query groups by the `UNIQUE` columns, so it
cannot return rows while the constraint exists. It is the receipt anyone can re-run,
including in the demo video. `ReminderSchemaTest` proves the constraint rejects a
duplicate.

Also covered: DST boundary `due_at` computation, short-notice `SKIPPED_LATE`,
useful-lead suppression, idempotent `POST` replay, cancel-clears-pending,
reschedule-recomputes-pending, `409` on key-reuse-with-different-body.

---

## 12. Alternatives rejected

### 12.1 Quartz Scheduler
Quartz is the usual Spring answer, and it is wrong here. Its clustered mode serialises
nodes through a lock row (`QRTZ_LOCKS`), the opposite of `SKIP LOCKED`, so it
contends under exactly the slot-boundary burst. It also brings 11 tables, misfire
semantics that need their own explanation, and a job store that duplicates state we
already have. This design needs one table, one index, and one query.

### 12.2 Kafka / RabbitMQ delayed messages
A message already sitting in a 24-hour delay cannot be retracted, and that rules this
option out. Cancel and reschedule (section 8.4) are routine events for service appointments,
and with queue-based delay the only workaround is a tombstone/filter table consulted
at consume time. That table is a database of reminder state, which is the design we
already have, plus a broker. In addition, 24h exceeds SQS's 15-minute delay cap, and
a broker outage loses in-flight timers unless they're persisted elsewhere.

### 12.3 Redis sorted set (`ZADD` by `due_at`)
Claiming is faster. But Redis becomes a second source of truth for "has this been
sent", and any divergence between Redis and Postgres is a duplicate or a miss. If
Redis is lost it has to be rebuilt from Postgres, which shows Postgres was the
authority all along. Revisit above ~5,000 claims/sec; the current rate is 12.

### 12.4 One cloud scheduler entry per appointment (EventBridge / Cloud Tasks)
1M scheduled entries/day hits per-account quotas and costs real money. It also makes
cancellation an API call that can fail independently of the database transaction,
which brings back the dual-write problem the table design avoids.

### 12.5 Computing due reminders on the fly
Without a row there is nowhere to record that a send happened, and there is no
`attempt_count`, lease, or audit trail, so there is no way to satisfy "provable".
Requirement F5 alone rules it out.

### 12.6 Separate scheduler microservice
This would mean two deployables, a network hop, and a distributed transaction between
"appointment created" and "reminders created", all to serve 12 sends/sec. One JAR
with two profiles (section 3) gets the same independent scaling without those failure modes.

---

## 13. Delivery plan

| Phase | Scope | Est. |
|---|---|---|
| 0 | Skeleton: Spring Boot 4.1, Flyway migrations, Testcontainers, docker-compose | 2h |
| 1 | Domain + `POST`/`GET`, validation, idempotent create | 4h |
| 2 | Reminder materialisation, `due_at`, `SKIPPED_LATE` | 2h |
| 3 | Dispatcher: claim loop, lease sweeper, stub sender | 4h |
| 4 | Retry/backoff, `DEAD`, attempt log | 3h |
| 5 | Cancel, reschedule | 2h |
| 6 | Concurrency + crash tests (section 11) | 4h |
| 7 | Metrics, health, `/reminders` proof endpoint | 2h |
| 8 | README, design diagram, demo video | 3h |

~26 hours, which fits the 7-day window with room to spare.

### 13.1 Demo video script (< 5 min, per the deliverable)

1. `docker compose up`: Postgres + app, with Flyway migrations in the log. (20s)
2. `curl POST /v1/appointments` with an appointment ~25h out returns `201`, showing
   both reminders `PENDING` with computed `dueAt`. (40s)
3. `psql`: `SELECT * FROM appointment; SELECT * FROM reminder;` to show the rows. (30s)
4. Fast-forward with `UPDATE reminder SET due_at = now()` (or the `/test/advance-clock`
   endpoint); the app logs show `NOTIFICATION … idempotencyKey=…`. (40s)
5. `psql` again: status `SENT`, `sent_at` populated, `reminder_attempt` row with
   `outcome=OK`. (30s)
6. The central proof: replay the same `POST` with the same `Idempotency-Key` and get
   `200` with no new rows. Then force a re-dispatch of the already-`SENT` reminder; the
   claim query returns nothing and the sender is not called. Then run the section 10.2
   duplicate query, which returns 0 rows. (60s)
7. `curl POST` with `scheduledAt` 3 hours out; T24H comes back `SKIPPED_LATE`. (30s)

---

## 14. Open questions the brief left open

These are written down instead of silently assumed, and each has a stated default so
nothing blocks.

| # | Question | Assumed default |
|---|---|---|
| Q1 | Is "24 hours before" exact elapsed time, or "the day before at a fixed hour" (e.g. 6 PM)? Many dealer systems do the latter to avoid 3 AM sends. | Exact 24h. |
| Q2 | After a reschedule, should an already-sent reminder be re-sent for the new time? (section 8.4) | Answered: yes. A fresh pair per appointment version. |
| Q3 | Quiet hours: suppress or shift a reminder that lands at 2 AM local? | Column present, enforcement off by default. |
| Q4 | Booked inside the window: send an immediate confirmation instead of skipping? (section 8.2) | Skip, don't substitute. |
| Q5 | SMS and email, or one preferred channel? | One channel per appointment; the data model supports both. |
| Q6 | Retention for reminder history: is there an audit/compliance requirement? | 13 months. |
| Q7 | Whose timezone governs, the dealership's or the customer's? A customer may book from another state. | Dealership's. |
| Q8 | Should a `NO_SHOW`/`COMPLETED` appointment suppress a still-pending reminder? | Yes, treated as cancel. |

---

## 15. With another week

In priority order. Each item closes a real gap:

1. A real provider adapter (Twilio/SES) behind the existing `NotificationSender`
   interface, with a Resilience4j circuit breaker and a per-tenant token bucket. The
   interface is already the seam, so this needs an implementation and no refactor.
2. A delivery receipts webhook that moves `SENT` to `DELIVERED` / `BOUNCED`. Right now
   "sent" means "the provider accepted it", which is not the same as "the customer saw
   it", and the current model can't express the difference.
3. Range partitioning and archival, with the section 10.2 partition-roll job. It is needed
   before ~100M rows, and it is cheap to add now but painful to retrofit once the table
   is hot.
4. A load test at 10× with k6 that publishes measured p99 claim latency and drain time
   against a 31,000-reminder burst, so section 2's numbers become measurements instead of
   arithmetic.
5. A chaos test in CI that sends `SIGKILL` to a worker mid-send on every build and
   asserts exactly one logical delivery. Running it on every build keeps the guarantee
   from rotting.
6. Quiet-hours shifting (Q3) and configurable reminder schedules per dealership (some
   want T48H + T2H). `reminder_type` is already an enum on the row, so this is a config
   table, not a schema change.
7. An admin timeline view: the `/reminders` endpoint rendered for support staff, so
   "did my customer get the reminder?" is no longer a DBA request.

