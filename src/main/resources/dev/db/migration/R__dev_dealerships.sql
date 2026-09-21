-- Demo dealerships for local runs. Loaded only with the dev profile (application-dev.properties).
-- Repeatable, so it re-runs whenever this file changes; ON CONFLICT keeps re-runs harmless.
INSERT INTO dealership (external_id, name, timezone) VALUES
    ('DLR-0042', 'Lakeshore Motors',     'America/Chicago'),
    ('DLR-0007', 'Hudson Auto',          'America/New_York'),
    ('DLR-0105', 'Desert Valley Motors', 'America/Phoenix')   -- no DST, so its offset never moves
ON CONFLICT (external_id) DO NOTHING;
