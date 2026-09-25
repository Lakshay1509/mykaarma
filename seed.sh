#!/usr/bin/env bash
# Books demo appointments through the API, so the rows are the ones the service writes.
# Each run adds a new batch. Needs the stack up: docker compose up -d
set -euo pipefail

API=${API:-http://localhost:8080}/v1/appointments
RUN=$(date +%s)
n=0

# Wall-clock time in the dealership's zone, with the offset that zone uses on that date.
at() { TZ=$1 date -d "$2" +%Y-%m-%dT%H:%M:00%:z; }

# book DEALERSHIP ZONE WHEN NAME CHANNEL CONTACT VEHICLE SERVICE
book() {
  local contact body key="seed-$RUN-$((++n))"   # not inside $(...), where the ++ would be lost
  if [[ $5 == SMS ]]; then contact="\"phone\": \"$6\""; else contact="\"email\": \"$6\""; fi
  body=$(curl -sS --fail-with-body -X POST "$API" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" -d @- <<JSON
{"dealershipId": "$1", "scheduledAt": "$(at "$2" "$3")", "serviceType": "$8",
 "customer": {"name": "$4", "channel": "$5", $contact},
 "vehicle": {"description": "$7"}}
JSON
  ) || { echo "$body" >&2; exit 1; }
  LAST_ID=$(jq -r .id <<<"$body")
  jq -r --arg who "$4" '"\(.localTime)  \($who)  \(.id)"' <<<"$body"
}

# T24H due in a minute or two, so the worker sends it while you watch the logs.
book DLR-0042 America/Chicago '+1441 minutes' 'Ana Marquez' SMS +14155550137 '2019 Honda Civic' OIL_CHANGE
book DLR-0007 America/New_York '+1443 minutes' 'Ben Carter' EMAIL ben.carter@example.com '2021 Toyota RAV4' BRAKE_INSPECTION

# Booked 2 to 3 hours out, so the T24H is already overdue and saved as SKIPPED_LATE (section 8.2).
book DLR-0007 America/New_York '+125 minutes' 'Chen Wei' SMS +12125550199 '2020 Subaru Outback' TIRE_ROTATION
book DLR-0042 America/Chicago '+3 hours' 'Dana Brooks' SMS +13125550164 '2016 Jeep Wrangler' CHECK_ENGINE

book DLR-0105 America/Phoenix '14:00 2 days' 'Diego Ramos' SMS +16025550142 '2018 Ford F-150' TIRE_ROTATION
book DLR-0042 America/Chicago '09:30 3 days' 'Emma Novak' EMAIL emma.novak@example.com '2022 Tesla Model 3' ANNUAL_SERVICE

book DLR-0007 America/New_York '10:00 5 days' 'Farah Haddad' SMS +16465550173 '2017 BMW 330i' OIL_CHANGE
curl -sS --fail-with-body -X DELETE "$API/$LAST_ID"
echo "  cancelled"

# The version-0 pair is cancelled and a version-1 pair is booked for the new time (section 8.4).
book DLR-0105 America/Phoenix '11:30 4 days' 'Grace Kim' SMS +14805550118 '2020 Mazda CX-5' BRAKE_INSPECTION
curl -sS --fail-with-body -o /dev/null -X PATCH "$API/$LAST_ID" -H 'If-Match: 0' \
  -H 'Content-Type: application/json' -d "{\"scheduledAt\": \"$(at America/Phoenix '15:00 6 days')\"}"
echo "  rescheduled to $(at America/Phoenix '15:00 6 days')"

cat <<'EOF'

Reminder rows (Ana, Ben and Chen turn SENT within a few minutes):
  docker compose exec postgres psql -U reminders -c "SELECT a.customer_name, r.reminder_type, r.appointment_version AS v, r.status, r.due_at FROM reminder r JOIN appointment a ON a.id = r.appointment_id ORDER BY a.id, v, r.due_at"
EOF
