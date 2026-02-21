package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import io.circe.*
import io.circe.derivation.ConfiguredCodec

import dbwatchdog.database.Persisted

final case class User(
    id: UUID,
    keycloakId: String,
    email: String,
    firstName: String,
    lastName: String,
    team: String,
    createdAt: Instant,
    updatedAt: Instant
) extends Persisted[UUID] derives ConfiguredCodec

final case class SyncUserRequest(
    keycloakId: String,
    email: String,
    firstName: String,
    lastName: String,
    team: String
) derives ConfiguredCodec

final case class UpdateUserRequest(
    firstName: Option[String],
    lastName: Option[String],
    team: Option[String]
)
