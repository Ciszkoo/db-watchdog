package dbwatchdog.routes

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.server.AuthMiddleware
import org.http4s.{AuthedRoutes, HttpRoutes}

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.{
  CreateDatabaseRequest,
  UpdateDatabaseRequest,
  UpsertTeamDatabaseGrantRequest,
  UpsertUserDatabaseAccessExtensionRequest
}
import dbwatchdog.service.AdminService

object AdminRoutes {
  def authedRoutes(
      adminService: AdminService
  ): AuthedRoutes[AuthUser, IO] =
    AuthedRoutes.of[AuthUser, IO] {
      case GET -> Root / "admin" / "teams" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            adminService.listTeams().flatMap(teams => Ok(teams.asJson))
          }
        }

      case GET -> Root / "admin" / "users" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            adminService.listUsers().flatMap(users => Ok(users.asJson))
          }
        }

      case GET -> Root / "admin" / "sessions" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            adminService.listSessions().flatMap(sessions => Ok(sessions.asJson))
          }
        }

      case GET -> Root / "admin" / "databases" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            adminService
              .listDatabases()
              .flatMap(databases => Ok(databases.asJson))
          }
        }

      case GET -> Root / "admin" / "team-database-grants" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            adminService
              .listTeamDatabaseGrants()
              .flatMap(grants => Ok(grants.asJson))
          }
        }

      case GET -> Root / "admin" / "user-database-access-extensions" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            adminService
              .listUserDatabaseAccessExtensions()
              .flatMap(extensions => Ok(extensions.asJson))
          }
        }

      case request @ POST -> Root / "admin" / "databases" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              payload <- decodeJson[CreateDatabaseRequest](request.req)
              database <- adminService.createDatabase(payload)
              response <- Ok(database.asJson)
            } yield response
          }
        }

      case request @ PUT -> Root / "admin" / "databases" / databaseId as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              parsedDatabaseId <- parseUuid(databaseId, "databaseId")
              payload <- decodeJson[UpdateDatabaseRequest](request.req)
              database <- adminService.updateDatabase(parsedDatabaseId, payload)
              response <- Ok(database.asJson)
            } yield response
          }
        }

      case POST -> Root / "admin" / "databases" / databaseId / "deactivate" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              parsedDatabaseId <- parseUuid(databaseId, "databaseId")
              database <- adminService.deactivateDatabase(parsedDatabaseId)
              response <- Ok(database.asJson)
            } yield response
          }
        }

      case POST -> Root / "admin" / "databases" / databaseId / "reactivate" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              parsedDatabaseId <- parseUuid(databaseId, "databaseId")
              database <- adminService.reactivateDatabase(parsedDatabaseId)
              response <- Ok(database.asJson)
            } yield response
          }
        }

      case request @ PUT -> Root / "admin" / "team-database-grants" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              payload <- decodeJson[UpsertTeamDatabaseGrantRequest](request.req)
              _ <- adminService.upsertTeamDatabaseGrant(payload)
              response <- NoContent()
            } yield response
          }
        }

      case DELETE -> Root / "admin" / "team-database-grants" / teamId / databaseId as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              parsedTeamId <- parseUuid(teamId, "teamId")
              parsedDatabaseId <- parseUuid(databaseId, "databaseId")
              _ <- adminService.deleteTeamDatabaseGrant(
                parsedTeamId,
                parsedDatabaseId
              )
              response <- NoContent()
            } yield response
          }
        }

      case request @ PUT -> Root / "admin" / "user-database-access-extensions" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              payload <- decodeJson[UpsertUserDatabaseAccessExtensionRequest](
                request.req
              )
              _ <- adminService.upsertUserDatabaseAccessExtension(payload)
              response <- NoContent()
            } yield response
          }
        }

      case DELETE -> Root / "admin" / "user-database-access-extensions" / userId / databaseId as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              parsedUserId <- parseUuid(userId, "userId")
              parsedDatabaseId <- parseUuid(databaseId, "databaseId")
              _ <- adminService.deleteUserDatabaseAccessExtension(
                parsedUserId,
                parsedDatabaseId
              )
              response <- NoContent()
            } yield response
          }
        }
    }

  def routes(
      adminService: AdminService
  )(using authMiddleware: AuthMiddleware[IO, AuthUser]): HttpRoutes[IO] =
    authMiddleware(authedRoutes(adminService))
}
