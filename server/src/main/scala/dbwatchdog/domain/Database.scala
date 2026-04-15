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
    technicalPassword: String,
    databaseName: String,
    createdAt: Instant,
    updatedAt: Instant,
    deactivatedAt: Option[Instant]
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

final case class UpdateDatabase(
    engine: String,
    host: String,
    port: Int,
    technicalUser: String,
    technicalPassword: String,
    databaseName: String
)
