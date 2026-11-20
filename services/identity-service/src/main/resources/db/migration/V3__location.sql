-- Location tracking (ADR-0015). Identity is the only service that stores positions.
-- Every table points at users with ON DELETE CASCADE, so deleting an account deletes its location data.

-- One row per user who has answered the consent question. No row = not asked yet.
-- OFF is an answer too: the person said no, so they are not asked again until the text changes.
CREATE TABLE location_consents (
    user_id      uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    level        text        NOT NULL CHECK (level IN ('FULL', 'MINIMUM', 'OFF')),
    text_version integer     NOT NULL CHECK (text_version >= 1),
    decided_at   timestamptz NOT NULL
);

-- One row per recorded point, for both levels. The latest position is the newest row of a user.
-- FULL keeps rows 30 days, MINIMUM keeps them 5 days (the retention job deletes older rows).
CREATE TABLE location_positions (
    id          uuid             PRIMARY KEY,
    user_id     uuid             NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    latitude    double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude   double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy_m  double precision NOT NULL CHECK (accuracy_m >= 0 AND accuracy_m <= 100000),
    recorded_at timestamptz      NOT NULL,
    received_at timestamptz      NOT NULL
);

-- The newest point of a user, and a user's history in time order.
CREATE INDEX location_positions_user_recorded_idx ON location_positions (user_id, recorded_at DESC);
-- The retention job deletes by age.
CREATE INDEX location_positions_recorded_idx ON location_positions (recorded_at);

-- Every Admin read of location data. subject_user_id is empty when the read covered everybody.
CREATE TABLE location_access_log (
    id              uuid        PRIMARY KEY,
    admin_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject_user_id uuid        REFERENCES users (id) ON DELETE CASCADE,
    kind            text        NOT NULL CHECK (kind IN ('LATEST_ALL', 'HISTORY')),
    accessed_at     timestamptz NOT NULL
);

CREATE INDEX location_access_log_accessed_idx ON location_access_log (accessed_at DESC);
CREATE INDEX location_access_log_admin_idx ON location_access_log (admin_id);
CREATE INDEX location_access_log_subject_idx ON location_access_log (subject_user_id);
