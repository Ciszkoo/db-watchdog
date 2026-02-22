package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import dbwatchdog.database.Persisted

// Database model
final case class Database(
    id: UUID,
    engine: String,
    host: String,
    port: Int,
    user: String,
    password: String, // Temporary solution to store password in the db
    schema: String,
    created_at: Instant,
    updated_at: Instant
) extends Persisted[UUID]

// Repository inputs
final case class CreateDatabase(
    engine: String,
    host: String,
    port: Int,
    user: String,
    password: String,
    schema: String
)
