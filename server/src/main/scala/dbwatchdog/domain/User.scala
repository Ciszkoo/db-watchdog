package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import io.circe.*
import io.circe.derivation.ConfiguredCodec

import dbwatchdog.database.Persisted

// Database model
final case class User(
    id: UUID,
    keycloakId: String,
    email: String,
    firstName: String,
    lastName: String,
    teamId: UUID,
    createdAt: Instant,
    updatedAt: Instant
) extends Persisted[UUID] derives ConfiguredCodec

// Repository inputs
final case class UpsertUserInput(
    keycloakId: String,
    email: String,
    firstName: String,
    lastName: String,
    teamName: String
)

final case class AuthenticatedUserSyncInput(
    keycloakId: String,
    email: String,
    firstName: String,
    lastName: String,
    team: String
)

final case class UpdateUserRequest(
    firstName: Option[String],
    lastName: Option[String],
    team: Option[String]
)
