# API

| Method | Path | |
|---|---|---|
| `POST` | `/v1/appointments` | Needs `Idempotency-Key`. `201` new, `200` replay, `409` key reused for a different booking |
| `GET` | `/v1/appointments/{id}` | Includes `version`, for rescheduling |
| `PATCH` | `/v1/appointments/{id}` | Reschedule. `If-Match: <version>`; `409` if stale or not `BOOKED` |
| `DELETE` | `/v1/appointments/{id}` | Cancel. `204` |
| `GET` | `/v1/appointments/{id}/reminders` | Every reminder row for the appointment, all versions, with status and idempotency key |
| `GET` | `/actuator/health`, `/actuator/prometheus` | Probes and metrics. Nothing else under `/actuator` is exposed |

```bash
curl -si localhost:8080/v1/appointments \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{"dealershipId": "DLR-0042",
       "scheduledAt": "'$(TZ=America/Chicago date -d '+25 hours' +%FT%H:%M:00%:z)'",
       "serviceType": "OIL_CHANGE",
       "customer": {"name": "Ana Marquez", "channel": "SMS", "phone": "+14155550137"},
       "vehicle": {"description": "2019 Honda Civic"}}'
```

A `400` means the request is malformed on its own terms: a missing field, a phone number
that isn't E.164, an SMS booking with no phone, a `scheduledAt` without an offset. A `422`
means the request is well formed but refused because of the server's state or clock: an
unknown dealership, a time in the past or more than 365 days out, an offset the dealership
doesn't use on that date. Bodies over 8 KB get `413`. Errors are RFC 9457
`application/problem+json` and name the field and the reason.

The `dev` profile, which `docker compose` runs, loads three dealerships: `DLR-0042` (Chicago), `DLR-0007` (New York), and
`DLR-0105` (Phoenix, which has no DST).
