package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import io.circe.derivation.ConfiguredCodec

import dbwatchdog.domain.Database as PersistedDatabase

final case class TeamResponse(
    id: UUID,
    name: String,
    createdAt: Instant,
    updatedAt: Instant
) derives ConfiguredCodec

object TeamResponse {
  def fromDomain(team: Team): TeamResponse =
    TeamResponse(
      id = team.id,
      name = team.name,
      createdAt = team.createdAt,
      updatedAt = team.updatedAt
    )
}

final case class AdminUserResponse(
    id: UUID,
    keycloakId: String,
    email: String,
    firstName: String,
    lastName: String,
    team: TeamResponse,
    createdAt: Instant,
    updatedAt: Instant
) derives ConfiguredCodec

object AdminUserResponse {
  def fromDomain(user: User, team: Team): AdminUserResponse =
    AdminUserResponse(
      id = user.id,
      keycloakId = user.keycloakId,
      email = user.email,
      firstName = user.firstName,
      lastName = user.lastName,
      team = TeamResponse.fromDomain(team),
      createdAt = user.createdAt,
      updatedAt = user.updatedAt
    )
}

final case class DatabaseResponse(
    id: UUID,
    engine: String,
    host: String,
    port: Int,
    technicalUser: String,
    databaseName: String,
    createdAt: Instant,
    updatedAt: Instant
) derives ConfiguredCodec

object DatabaseResponse {
  def fromDomain(database: PersistedDatabase): DatabaseResponse =
    DatabaseResponse(
      id = database.id,
      engine = database.engine,
      host = database.host,
      port = database.port,
      technicalUser = database.technicalUser,
      databaseName = database.databaseName,
      createdAt = database.createdAt,
      updatedAt = database.updatedAt
    )
}

final case class AdminDatabaseSessionResponse(
    id: UUID,
    credentialId: UUID,
    clientAddr: String,
    startedAt: Instant,
    endedAt: Option[Instant],
    bytesSent: Option[Long],
    bytesReceived: Option[Long],
    user: AdminUserResponse,
    database: DatabaseResponse
) derives ConfiguredCodec

object AdminDatabaseSessionResponse {
  def fromDomain(
      session: DatabaseSession,
      user: AdminUserResponse,
      database: DatabaseResponse
  ): AdminDatabaseSessionResponse =
    AdminDatabaseSessionResponse(
      id = session.id,
      credentialId = session.credentialId,
      clientAddr = session.clientAddr,
      startedAt = session.startedAt,
      endedAt = session.endedAt,
      bytesSent = session.bytesSent,
      bytesReceived = session.bytesReceived,
      user = user,
      database = database
    )
}

final case class CreateDatabaseRequest(
    engine: String,
    host: String,
    port: Int,
    technicalUser: String,
    technicalPassword: String,
    databaseName: String
) derives ConfiguredCodec

final case class UpsertTeamDatabaseGrantRequest(
    teamId: UUID,
    databaseId: UUID
) derives ConfiguredCodec

final case class UpsertUserDatabaseAccessExtensionRequest(
    userId: UUID,
    databaseId: UUID,
    expiresAt: Option[Instant]
) derives ConfiguredCodec

final case class EffectiveDatabaseAccessResponse(
    databaseId: UUID,
    engine: String,
    host: String,
    port: Int,
    databaseName: String,
    loginIdentifier: String,
    accessSource: String,
    extensionExpiresAt: Option[Instant]
) derives ConfiguredCodec

final case class IssueOtpResponse(
    credentialId: UUID,
    otp: String,
    expiresAt: Instant,
    database: DatabaseResponse
) derives ConfiguredCodec
