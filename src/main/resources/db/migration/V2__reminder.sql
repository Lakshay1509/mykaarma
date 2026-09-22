CREATE TABLE reminder (
    id               BIGSERIAL    PRIMARY KEY,
    appointment_id   BIGINT       NOT NULL REFERENCES appointment (id) ON DELETE CASCADE,
    reminder_type    VARCHAR(16)  NOT NULL,
    due_at           TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    idempotency_key  UUID         NOT NULL,
    attempt_count    SMALLINT     NOT NULL DEFAULT 0,
    claimed_by       VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    sent_at          TIMESTAMPTZ,
    last_error       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The no-duplicates requirement (§7.3): a second reminder of the same type can't exist.
    CONSTRAINT uq_reminder UNIQUE (appointment_id, reminder_type),
    CONSTRAINT ck_reminder_type CHECK (reminder_type IN ('T24H', 'T2H')),
    CONSTRAINT ck_reminder_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'SENT', 'DEAD', 'SKIPPED_LATE', 'CANCELLED'))
);

-- Keep these partial: indexing only PENDING and CLAIMED rows keeps claims fast past 100M rows (§4).
CREATE INDEX idx_reminder_due  ON reminder (due_at)           WHERE status = 'PENDING';
CREATE INDEX idx_reminder_lease ON reminder (lease_expires_at) WHERE status = 'CLAIMED';

CREATE TABLE reminder_attempt (
    id           BIGSERIAL    PRIMARY KEY,
    reminder_id  BIGINT       NOT NULL REFERENCES reminder (id) ON DELETE CASCADE,
    attempt_no   SMALLINT     NOT NULL,
    worker_id    VARCHAR(64)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    outcome      VARCHAR(16),
    provider_ref VARCHAR(128),
    error        TEXT,

    CONSTRAINT ck_attempt_outcome CHECK (outcome IN ('OK', 'RETRYABLE', 'PERMANENT', 'ABANDONED'))
);
CREATE INDEX idx_attempt_reminder ON reminder_attempt (reminder_id);
