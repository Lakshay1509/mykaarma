ALTER TABLE reminder ADD COLUMN appointment_version INT NOT NULL DEFAULT 0;
ALTER TABLE reminder DROP CONSTRAINT uq_reminder,
    ADD CONSTRAINT uq_reminder UNIQUE (appointment_id, reminder_type, appointment_version);
