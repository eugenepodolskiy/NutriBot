-- User profile additions
ALTER TABLE users ADD COLUMN sex          VARCHAR(10);
ALTER TABLE users ADD COLUMN fiber_goal   DOUBLE PRECISION;
ALTER TABLE users ADD COLUMN water_goal   DOUBLE PRECISION;

-- Food log fiber tracking
ALTER TABLE food_log ADD COLUMN fiber DOUBLE PRECISION;
