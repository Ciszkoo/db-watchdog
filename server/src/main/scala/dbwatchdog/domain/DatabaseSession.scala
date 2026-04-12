package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import io.circe.derivation.ConfiguredCodec

import dbwatchdog.database.Persisted

final case class DatabaseSession(
    id: UUID,
    userId: UUID,
    databaseId: UUID,
    credentialId: UUID,
    clientAddr: String,
    startedAt: Instant,
    endedAt: Option[Instant],
    bytesSent: Option[Long],
    bytesReceived: Option[Long],
    createdAt: Instant,
    updatedAt: Instant
) extends Persisted[UUID] derives ConfiguredCodec

final case class CreateDatabaseSessionInput(
    userId: UUID,
    databaseId: UUID,
    credentialId: UUID,
    clientAddr: String,
    startedAt: Instant
)
