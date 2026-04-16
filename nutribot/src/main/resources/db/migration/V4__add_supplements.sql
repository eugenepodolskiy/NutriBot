CREATE TABLE supplements (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES users(id) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    dose        DOUBLE PRECISION,
    unit        VARCHAR(50),
    reminder_times VARCHAR(255),
    active      BOOLEAN NOT NULL DEFAULT TRUE
);
