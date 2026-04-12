package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import io.circe.derivation.ConfiguredCodec

import dbwatchdog.database.Persisted

// Database model
final case class Database(
    id: UUID,
    engine: String,
    host: String,
    port: Int,
    technicalUser: String,
    technicalPassword: String, // Temporary solution until credential hardening is implemented end to end.
    databaseName: String,
    createdAt: Instant,
    updatedAt: Instant
) extends Persisted[UUID] derives ConfiguredCodec

// Repository inputs
final case class CreateDatabase(
    engine: String,
    host: String,
    port: Int,
    technicalUser: String,
    technicalPassword: String,
    databaseName: String
)
