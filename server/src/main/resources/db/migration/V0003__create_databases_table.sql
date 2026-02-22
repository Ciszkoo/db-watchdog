-- Create "databases" table
CREATE TABLE databases (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  engine TEXT NOT NULL,
  host TEXT NOT NULL,
  port INTEGER NOT NULL,
  "user" TEXT NOT NULL,
  -- Password only temp, not a good idea to store it in the db,
  -- especially as a plain text
  password TEXT NOT NULL,
  "schema" TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);