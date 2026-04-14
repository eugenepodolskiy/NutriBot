CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    age INTEGER,
    weight_kg DOUBLE PRECISION,
    height_cm DOUBLE PRECISION,
    activity_level VARCHAR(255),
    goal_type VARCHAR(255),
    calorie_goal DOUBLE PRECISION,
    protein_goal DOUBLE PRECISION,
    fat_goal DOUBLE PRECISION,
    carb_goal DOUBLE PRECISION,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE food (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    calories_per_100g DOUBLE PRECISION,
    protein_per_100g DOUBLE PRECISION,
    carbs_per_100g DOUBLE PRECISION,
    fat_per_100g DOUBLE PRECISION,
    food_type VARCHAR(255),
    source VARCHAR(255)
);

CREATE TABLE food_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    logged_at TIMESTAMP NOT NULL DEFAULT NOW(),
    meal_type VARCHAR(255),
    description TEXT,
    grams DOUBLE PRECISION,
    calories DOUBLE PRECISION,
    protein DOUBLE PRECISION,
    carbs DOUBLE PRECISION,
    fat DOUBLE PRECISION,
    source VARCHAR(255)
);