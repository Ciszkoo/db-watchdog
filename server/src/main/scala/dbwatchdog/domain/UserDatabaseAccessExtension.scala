package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import io.circe.derivation.ConfiguredCodec

import dbwatchdog.database.Persisted

final case class UserDatabaseAccessExtension(
    id: UUID,
    userId: UUID,
    databaseId: UUID,
    expiresAt: Option[Instant],
    createdAt: Instant,
    updatedAt: Instant
) extends Persisted[UUID] derives ConfiguredCodec

final case class UpsertUserDatabaseAccessExtensionInput(
    userId: UUID,
    databaseId: UUID,
    expiresAt: Option[Instant]
)
