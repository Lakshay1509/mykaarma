# Proving "never twice"

How the guarantee in the [README](../README.md#the-guarantee) holds up, and the tests that
show it. Section references like section 7.3 point into [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md).

## Where a duplicate can still happen

A worker that dies between the provider call and the settle is the one place a duplicate
can still reach a phone. I measured it by stopping a worker in the middle of a
4,000-reminder batch (section 7.3):

| Stop | Re-sent | Why |
|---|---|---|
| `SIGTERM` (deploy, scale-in) | 0 of 4,000 | Spring waits for the running poll, so the batch settles before exit |
| `kill -9` (OOM, eviction) | 50 of 4,000 | Rows already sent, still waiting for a pool connection to settle |

Both runs ended with exactly one `SENT` row per reminder. Whether those 50 reach the
customer twice depends on the provider: Amazon SES `SendEmail` takes no idempotency key,
so with SES they would. They carry the same key either way, so they are easy to find in the
logs.

Marking a reminder `SENT` before sending would close that window and open a worse one: a
crash before the send would lose the reminder for good, and the row would say it went out.
The service takes at-least-once (never lose a reminder) and relies on the stored key to
collapse the repeats.

## Check it yourself

With the stack up and seeded:

```bash
sql() { docker compose exec -T postgres psql -U reminders "$@"; }

# Is any reminder stored twice? Expect (0 rows).
sql -c "SELECT appointment_id, reminder_type, appointment_version, count(*)
          FROM reminder GROUP BY 1, 2, 3 HAVING count(*) > 1"

# Try to store one twice. Expect: violates unique constraint "uq_reminder".
sql -c "INSERT INTO reminder (appointment_id, reminder_type, appointment_version,
                              due_at, status, idempotency_key)
        SELECT appointment_id, reminder_type, appointment_version,
               now(), 'PENDING', gen_random_uuid()
          FROM reminder LIMIT 1"
```

The first query groups by the unique columns, so it can't return a row while the
constraint exists. The second shows the constraint is live.

Then the concurrency proof, about ten seconds once the container is up:

```bash
source env.sh && ./mvnw test -Dtest=DispatcherConcurrencyTest
```

- `tenWorkersRacingOneReminder_sendExactlyOnce` builds ten dispatchers with their own
  worker ids and releases them onto one due reminder with a `CyclicBarrier`. It asserts one
  provider call and `SENT:1`, the status and the claim count.
- `workerDyingAfterTheProviderAcceptedTheMessage_isResentUnderTheSameKey_andSettledOnce`
  lets the provider record the call, then kills the worker before it settles. After the
  lease expires a second worker resends. It asserts two provider calls with the identical
  key, attempts `1:never settled,2:OK`, and one `SENT` row.
- `randomWorkloadWithInjectedFailures_sendsEveryReminderOnceUnderItsOwnKey` pushes 20,000
  reminders through ten workers while the provider times out on 10% of calls and kills the
  worker mid-send on 2%. Nothing is left pending, each key the provider accepted was
  accepted once and belongs to a `SENT` row, and the duplicate query returns nothing.

## Every invariant test was broken on purpose

To confirm each of these tests can fail, I broke the mechanism it guards, watched the test
go red, and put the mechanism back.

| Break this | This goes red |
|---|---|
| Delete `FOR UPDATE SKIP LOCKED` from the claim query | The ten-worker race: all ten claim the row, `SENT:10` |
| Delete only `SKIP LOCKED` | `ReminderClaimTest#rowsLockedByOneWorker_areSkippedByAnotherInsteadOfWaitedOn`. The race test stays green here, because workers queue for the lock instead of double-claiming, which is why this second test exists |
| Generate the idempotency key per claim | The crash test |
| A sweeper that never frees crashed rows | The random workload: 419 reminders stuck |
| Settle a timeout as `SENT` | The random workload |
| Resolve a local time by zone and ignore its offset | `AppointmentControllerTest#localTimeRepeatedByFallBack_isBookedAtTheInstantItsOffsetNames` |
| Drop the `PENDING` filter from the lag gauge, or turn `min` into `max` | `ReminderLagTest` |
| Remove a metric increment | `DispatcherTest`, `SendOutcomeTest` |

There is no H2 anywhere in the build. H2 doesn't implement `SKIP LOCKED`, so the race test
would pass on it while proving nothing about Postgres. Every `@SpringBootTest` imports
`TestcontainersConfiguration`.

## Failure modes and corner cases

Each row has a test that fails if the behaviour changes. The accepted risks are in section 9.

| Case | What happens | Test |
|---|---|---|
| Ten workers poll one due reminder at once | One claims it; one send | `DispatcherConcurrencyTest#tenWorkersRacing…` |
| Worker dies after the provider accepted the message | Resent 60 to 90 s later under the same key, settled once | `DispatcherConcurrencyTest#workerDying…` |
| A send outlives its 60 s lease | Its late settle changes nothing; the attempt is recorded `ABANDONED` | `InFlightCapTest#sendThatOutlivesItsLease…` |
| A reminder whose sends keep dying | `DEAD` on the sixth | `ReminderClaimTest#sixthSendToDieMidFlight…` |
| Provider times out | `PENDING`, exponential backoff | `SendOutcomeTest#timeout_returnsReminderToPending…` |
| Provider down for hours | Backoff caps at 15 min; retries continue while the reminder is useful | `SendOutcomeTest#timeoutHoursIntoAnOutage…` |
| Invalid phone number | `DEAD` on the first attempt | `SendOutcomeTest#invalidNumber…` |
| Provider hangs | No database connection held; the database still answers | `SlowVendorTest` |
| Workers down for hours | 24-hour reminder 4 h late sends; 2-hour reminder 2 h late is skipped | `DispatcherTest#t2hTwoHoursLate…` |
| App clocks disagree | Only Postgres `now()` decides what is due | `ReminderClaimTest#claim_leasesOnly…DatabaseClock` |
| Booked 3 hours out | 24-hour reminder `SKIPPED_LATE`, 2-hour `PENDING` | `AppointmentControllerTest#remindersEndpoint…` |
| Client retries a `POST`, even concurrently | One appointment; every retry gets it | `ConcurrentBookingTest` |
| Reminder insert fails mid-booking | Appointment rolls back too | `…#failedReminderInsert_leavesNoAppointmentBehind` |
| Cancel while the message is with the provider | Row ends `SENT`, matching what the customer got | `LifecycleRaceTest#cancelWhileTheSendIsInFlight…` |
| Ten reschedules race on one version | One wins and writes one new pair | `LifecycleRaceTest#tenReschedulesOnOneVersion…` |
| 24 hours before spans a DST change | 24 elapsed hours | `ReminderTypeTest#t24hAcrossSpringForward…` |
| 02:30 on spring-forward night | `422` | `…#localTimeSkippedBySpringForward_isRejected` |
| 01:30 on fall-back night | Booked at the instant its offset names | `…#localTimeRepeatedByFallBack…` |
| Chicago time sent with `-06:00` in July | `422` | `…#offsetTheDealershipIsNotOnThatDay_isRejected` |
| Duplicate reminder row inserted by hand, or a backup restored twice | Rejected by `uq_reminder` | `ReminderSchemaTest#secondReminderOfTheSameType…` |
| Misspelt status | Rejected by a `CHECK`. It would fall outside the partial index and never send | `ReminderSchemaTest#misspeltStatus…` |
