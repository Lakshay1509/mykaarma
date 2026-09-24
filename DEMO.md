# Live demo

Copy commands from this file in your editor, not from a chat window: chat wraps long
lines, and a wrap in the middle of a command splits it in two.

## Before the interviewer joins

```bash
cd ~/Desktop/mykaarma
docker compose up -d          # add --build only if you changed code
curl localhost:8080/actuator/health
```

- Terminal 1 (logs): `docker compose logs -f app | grep --line-buffered -E 'APPOINTMENT|NOTIFICATION'`
- Terminal 2: everything below
- Browser: <http://localhost:9090> (Prometheus)

Never `docker compose down -v`: it deletes the seeded data.

## Setup (Terminal 2, once)

A fresh key every run: an old key with a new time is rejected with `409`.

```bash
sql() { docker compose exec -T postgres psql -U reminders "$@"; }
KEY=demo-$(date +%s)
WHEN=$(TZ=America/Chicago date -d '+25 hours' +%FT%H:%M:00%:z)
BODY='{"dealershipId": "DLR-0042",
  "scheduledAt": "'$WHEN'",
  "serviceType": "OIL_CHANGE",
  "customer": {"name": "Riya Shah", "channel": "SMS",
               "phone": "+14155550188"},
  "vehicle": {"description": "2023 Hyundai Creta"}}'
```

## 1. Book an appointment → `201`

```bash
curl -si -X POST localhost:8080/v1/appointments \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "$BODY" | tee /tmp/demo.txt; echo
ID=$(tail -1 /tmp/demo.txt | jq -r .id)
```

## 2. Its two reminders: `PENDING`, due 24h and 2h before

```bash
curl -s localhost:8080/v1/appointments/$ID/reminders | jq
```

## 3. Fast-forward the 24h reminder → Terminal 1 logs the send within a second

```bash
sql <<SQL
UPDATE reminder SET due_at = now()
 WHERE reminder_type = 'T24H'
   AND appointment_id = (SELECT id FROM appointment
                          WHERE idempotency_key = '$KEY');
SQL
```

## 4. The rows: `SENT`, `sent_at` set, one `OK` attempt

```bash
sql <<SQL
SELECT r.reminder_type, r.status, r.sent_at, t.outcome
  FROM reminder r
  LEFT JOIN reminder_attempt t ON t.reminder_id = r.id
 WHERE r.appointment_id = (SELECT id FROM appointment
                            WHERE idempotency_key = '$KEY')
 ORDER BY r.due_at;
SQL
```

## 5. Never twice

Same request again → `200`, same id, no new row:

```bash
curl -si -X POST localhost:8080/v1/appointments \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "$BODY" | grep -E '^HTTP|^\{'
```

Sneak in a second copy of a sent reminder → `violates unique constraint "uq_reminder"`:

```bash
sql <<SQL
INSERT INTO reminder (appointment_id, reminder_type,
       appointment_version, due_at, status, idempotency_key)
SELECT appointment_id, reminder_type, appointment_version,
       now(), 'PENDING', gen_random_uuid()
  FROM reminder WHERE status = 'SENT' LIMIT 1;
SQL
```

Look for duplicates in the whole table → `(0 rows)`:

```bash
sql <<SQL
SELECT appointment_id, reminder_type, appointment_version, count(*)
  FROM reminder
 GROUP BY 1, 2, 3
HAVING count(*) > 1;
SQL
```

> The app protects the user; the database constraint makes a duplicate impossible,
> even if my code has a bug.

## 6. Short-notice booking → `SKIPPED_LATE`

Already in the seed data: Chen and Dana booked 2–3 hours out, so their 24h reminder
was overdue at booking and never goes out.

```bash
sql <<SQL
SELECT a.customer_name, r.reminder_type, r.status
  FROM reminder r JOIN appointment a ON a.id = r.appointment_id
 WHERE r.status = 'SKIPPED_LATE';
SQL
```

## 7. Monitoring: lag climbs when the workers stop

In Prometheus: type `reminder_lag_seconds`, press Execute, open the Graph tab, range 15m.

```bash
APP_WORKER_ENABLED=false docker compose up -d app   # API up, worker off
sql <<SQL
UPDATE reminder SET due_at = now()
 WHERE reminder_type = 'T2H'
   AND appointment_id = (SELECT id FROM appointment
                          WHERE idempotency_key = '$KEY');
SQL
```

Refresh the graph for a minute: the line climbs. Then bring the worker back, and it
drops to 0 as Terminal 1 logs the send:

```bash
docker compose up -d app
```

> One number catches a dead dispatcher, a slow database or too few workers. I alert
> when it's over 300 seconds.

More graphs:

| Expression | Shows |
|---|---|
| `sum by (outcome) (rate(reminder_send_seconds_count[1m]))` | sends per second, OK vs failing |
| `histogram_quantile(0.99, sum by (le, channel) (rate(reminder_send_seconds_bucket[5m])))` | p99 send time per channel |
| `reminders_dead_total`, `reminders_skipped_late_total` | what went wrong |

## 8. "How do you know it's safe under concurrency?"

Ten workers race for one reminder; the test asserts exactly one send (~30s):

```bash
source env.sh
./mvnw test \
  -Dtest='DispatcherConcurrencyTest#tenWorkersRacingOneReminder_sendExactlyOnce'
```
