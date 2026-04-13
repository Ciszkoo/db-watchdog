package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import io.circe.derivation.ConfiguredCodec

import dbwatchdog.database.Persisted

final case class TeamDatabaseGrant(
    id: UUID,
    teamId: UUID,
    databaseId: UUID,
    createdAt: Instant,
    updatedAt: Instant
) extends Persisted[UUID] derives ConfiguredCodec

final case class UpsertTeamDatabaseGrantInput(
    teamId: UUID,
    databaseId: UUID
)
