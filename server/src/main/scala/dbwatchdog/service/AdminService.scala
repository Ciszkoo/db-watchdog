package dbwatchdog.service

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.implicits.*
import doobie.ConnectionIO

import dbwatchdog.database.Database
import dbwatchdog.domain.{
  AdminDatabaseSessionResponse,
  AdminUserResponse,
  CreateDatabase,
  CreateDatabaseRequest,
  Database as PersistedDatabase,
  DatabaseResponse,
  Team,
  TeamResponse,
  UpsertTeamDatabaseGrantInput,
  UpsertTeamDatabaseGrantRequest,
  UpsertUserDatabaseAccessExtensionInput,
  UpsertUserDatabaseAccessExtensionRequest
}
import dbwatchdog.repository.Repositories

trait AdminService {
  def listTeams(): IO[List[TeamResponse]]
  def listUsers(): IO[List[AdminUserResponse]]
  def listSessions(): IO[List[AdminDatabaseSessionResponse]]
  def listDatabases(): IO[List[DatabaseResponse]]
  def createDatabase(request: CreateDatabaseRequest): IO[DatabaseResponse]
  def upsertTeamDatabaseGrant(
      request: UpsertTeamDatabaseGrantRequest
  ): IO[Unit]
  def deleteTeamDatabaseGrant(
      teamId: UUID,
      databaseId: UUID
  ): IO[Unit]
  def upsertUserDatabaseAccessExtension(
      request: UpsertUserDatabaseAccessExtensionRequest
  ): IO[Unit]
  def deleteUserDatabaseAccessExtension(
      userId: UUID,
      databaseId: UUID
  ): IO[Unit]
}

object AdminService {
  def make(
      repos: Repositories,
      db: Database
  ): AdminService =
    new AdminService {
      def listTeams(): IO[List[TeamResponse]] =
        db.transact(
          repos.teams.list.map(_.map(TeamResponse.fromDomain))
        )

      def listUsers(): IO[List[AdminUserResponse]] =
        db.transact(
          for {
            teams <- repos.teams.list
            users <- repos.users.list
            teamIndex = teams.map(team => team.id -> team).toMap
            responses <- users.traverse { user =>
              teamIndex
                .get(user.teamId)
                .liftTo[ConnectionIO](
                  IllegalStateException(
                    s"Missing team ${user.teamId} for user ${user.id}"
                  )
                )
                .map(team => AdminUserResponse.fromDomain(user, team))
            }
          } yield responses
        )

      def listSessions(): IO[List[AdminDatabaseSessionResponse]] =
        db.transact(
          for {
            sessions <- repos.databaseSessions.list
            teams <- repos.teams.list
            users <- repos.users.list
            databases <- repos.databases.findByIds(
              sessions.map(_.databaseId).toSet
            )
            teamIndex = teams.map(team => team.id -> team).toMap
            usersWithTeams <- users.traverse { user =>
              teamIndex
                .get(user.teamId)
                .liftTo[ConnectionIO](
                  IllegalStateException(
                    s"Missing team ${user.teamId} for user ${user.id}"
                  )
                )
                .map(team =>
                  user.id -> AdminUserResponse.fromDomain(user, team)
                )
            }
            userIndex = usersWithTeams.toMap
            databaseIndex = databases
              .map(database => database.id -> database)
              .toMap
            responses <- sessions.traverse { session =>
              for {
                user <- userIndex
                  .get(session.userId)
                  .liftTo[ConnectionIO](
                    IllegalStateException(
                      s"Missing user ${session.userId} for session ${session.id}"
                    )
                  )
                database <- databaseIndex
                  .get(session.databaseId)
                  .liftTo[ConnectionIO](
                    IllegalStateException(
                      s"Missing database ${session.databaseId} for session ${session.id}"
                    )
                  )
              } yield AdminDatabaseSessionResponse.fromDomain(
                session,
                user,
                DatabaseResponse.fromDomain(database)
              )
            }
          } yield responses
        )

      def listDatabases(): IO[List[DatabaseResponse]] =
        db.transact(
          repos.databases.list.map(_.map(DatabaseResponse.fromDomain))
        )

      def createDatabase(
          request: CreateDatabaseRequest
      ): IO[DatabaseResponse] =
        db.transact(
          repos.databases
            .insert(
              CreateDatabase(
                engine = request.engine,
                host = request.host,
                port = request.port,
                technicalUser = request.technicalUser,
                technicalPassword = request.technicalPassword,
                databaseName = request.databaseName
              )
            )
            .map(DatabaseResponse.fromDomain)
        )

      def upsertTeamDatabaseGrant(
          request: UpsertTeamDatabaseGrantRequest
      ): IO[Unit] =
        db.transact(
          for {
            _ <- requireTeam(request.teamId)
            _ <- requireDatabase(request.databaseId)
            _ <- repos.teamDatabaseGrants.upsert(
              UpsertTeamDatabaseGrantInput(
                teamId = request.teamId,
                databaseId = request.databaseId
              )
            )
          } yield ()
        )

      def deleteTeamDatabaseGrant(
          teamId: UUID,
          databaseId: UUID
      ): IO[Unit] =
        db.transact(
          for {
            _ <- requireTeam(teamId)
            _ <- requireDatabase(databaseId)
            _ <- repos.teamDatabaseGrants.delete(teamId, databaseId)
          } yield ()
        )

      def upsertUserDatabaseAccessExtension(
          request: UpsertUserDatabaseAccessExtensionRequest
      ): IO[Unit] = {
        val now = Instant.now()

        db.transact(
          for {
            _ <- validateExpiry(request.expiresAt, now)
            _ <- requireUser(request.userId)
            _ <- requireDatabase(request.databaseId)
            _ <- repos.userDatabaseAccessExtensions.upsert(
              UpsertUserDatabaseAccessExtensionInput(
                userId = request.userId,
                databaseId = request.databaseId,
                expiresAt = request.expiresAt
              )
            )
          } yield ()
        )
      }

      def deleteUserDatabaseAccessExtension(
          userId: UUID,
          databaseId: UUID
      ): IO[Unit] =
        db.transact(
          for {
            _ <- requireUser(userId)
            _ <- requireDatabase(databaseId)
            _ <- repos.userDatabaseAccessExtensions.delete(userId, databaseId)
          } yield ()
        )

      private def requireTeam(teamId: UUID): ConnectionIO[Team] =
        repos.teams
          .findById(teamId)
          .flatMap(
            _.liftTo[ConnectionIO](
              ServiceError.NotFound(s"Team $teamId not found")
            )
          )

      private def requireUser(
          userId: UUID
      ): ConnectionIO[dbwatchdog.domain.User] =
        repos.users
          .findById(userId)
          .flatMap(
            _.liftTo[ConnectionIO](
              ServiceError.NotFound(s"User $userId not found")
            )
          )

      private def requireDatabase(
          databaseId: UUID
      ): ConnectionIO[PersistedDatabase] =
        repos.databases
          .findById(databaseId)
          .flatMap(
            _.liftTo[ConnectionIO](
              ServiceError.NotFound(s"Database $databaseId not found")
            )
          )

      private def validateExpiry(
          expiresAt: Option[Instant],
          now: Instant
      ): ConnectionIO[Unit] =
        expiresAt match
          case Some(value) if !value.isAfter(now) =>
            ServiceError
              .BadRequest("expiresAt must be in the future")
              .raiseError[ConnectionIO, Unit]
          case _ =>
            ().pure[ConnectionIO]
    }
}
