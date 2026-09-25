CREATE TABLE dealership (
    id                BIGSERIAL    PRIMARY KEY,
    external_id       VARCHAR(64)  NOT NULL UNIQUE,
    name              VARCHAR(200) NOT NULL,
    timezone          VARCHAR(64)  NOT NULL,   -- IANA, e.g. America/Chicago
    quiet_hours_start TIME,                    -- not enforced yet, see section 14 Q3
    quiet_hours_end   TIME,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE appointment (
    id                  BIGSERIAL    PRIMARY KEY,
    public_id           UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    dealership_id       BIGINT       NOT NULL REFERENCES dealership (id),
    customer_name       VARCHAR(200) NOT NULL,
    customer_phone      VARCHAR(20),               -- E.164
    customer_email      VARCHAR(320),
    channel             VARCHAR(16)  NOT NULL,
    vehicle_vin         VARCHAR(17),
    vehicle_description VARCHAR(200) NOT NULL,
    service_type        VARCHAR(64)  NOT NULL,     -- dealer-specific codes, so no CHECK
    scheduled_at        TIMESTAMPTZ  NOT NULL,
    local_tz            VARCHAR(64)  NOT NULL,     -- dealership tz at booking time
    status              VARCHAR(16)  NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    version             INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_appt_idem UNIQUE (dealership_id, idempotency_key),
    CONSTRAINT ck_channel CHECK (
        (channel = 'SMS'   AND customer_phone IS NOT NULL) OR
        (channel = 'EMAIL' AND customer_email IS NOT NULL)),
    CONSTRAINT ck_status CHECK (status IN ('BOOKED', 'CANCELLED', 'COMPLETED', 'NO_SHOW'))
);
