-- Supplement intake log
CREATE TABLE supplement_log (
    id            BIGSERIAL PRIMARY KEY,
    supplement_id BIGINT REFERENCES supplements(id) ON DELETE CASCADE NOT NULL,
    user_id       BIGINT REFERENCES users(id)       ON DELETE CASCADE NOT NULL,
    taken_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    taken         BOOLEAN   NOT NULL DEFAULT TRUE
);

-- Timezone support (stored as ZoneOffset string, e.g. "UTC", "+03:00", "-05:00")
ALTER TABLE users ADD COLUMN timezone VARCHAR(50) NOT NULL DEFAULT 'UTC';
