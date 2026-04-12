package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import io.circe.derivation.ConfiguredCodec

import dbwatchdog.database.Persisted

final case class TemporaryAccessCredential(
    id: UUID,
    userId: UUID,
    databaseId: UUID,
    otpHash: String,
    expiresAt: Instant,
    usedAt: Option[Instant],
    createdAt: Instant,
    updatedAt: Instant
) extends Persisted[UUID] derives ConfiguredCodec

final case class CreateTemporaryAccessCredentialInput(
    userId: UUID,
    databaseId: UUID,
    otpHash: String,
    expiresAt: Instant
)
