-- Create "teams" table
CREATE TABLE teams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Migrate existing teams from "users" table
INSERT INTO teams (name)
SELECT DISTINCT team
FROM users
WHERE team IS NOT NULL AND team <> '';

-- Add FK column
ALTER TABLE users
    ADD COLUMN team_id UUID REFERENCES teams(id);

-- Fill "users" with "team_id"
UPDATE users u
SET team_id = t.id
FROM teams t
WHERE t.name = u.team;

-- Set "team_id" as NOT NULL
ALTER TABLE users
    ALTER COLUMN team_id SET NOT NULL;

-- Remove obsolete "team" column
ALTER TABLE users
    DROP COLUMN team;