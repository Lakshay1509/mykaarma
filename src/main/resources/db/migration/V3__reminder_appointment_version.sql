-- A reschedule writes a fresh pair of reminders for the new time (§8.4, Q2 answered yes).
-- "Never twice" now holds per appointment version: an old pair keeps its SENT rows,
-- and no pair can be written twice.
ALTER TABLE reminder ADD COLUMN appointment_version INT NOT NULL DEFAULT 0;
ALTER TABLE reminder DROP CONSTRAINT uq_reminder,
    ADD CONSTRAINT uq_reminder UNIQUE (appointment_id, reminder_type, appointment_version);
