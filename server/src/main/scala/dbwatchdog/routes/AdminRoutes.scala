package dbwatchdog.routes

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.server.AuthMiddleware
import org.http4s.{AuthedRoutes, HttpRoutes, Request}

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.{
  AdminDatabaseSessionState,
  CreateDatabaseRequest,
  ListAdminSessionsQuery,
  UpdateDatabaseRequest,
  UpsertTeamDatabaseGrantRequest,
  UpsertUserDatabaseAccessExtensionRequest
}
import dbwatchdog.service.{AdminService, ServiceError}

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

      case request @ GET -> Root / "admin" / "sessions" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              query <- decodeListSessionsQuery(request.req)
              sessions <- adminService.listSessions(query)
              response <- Ok(sessions.asJson)
            } yield response
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

      case POST -> Root / "admin" / "databases" / "technical-credentials" / "rewrap" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            adminService
              .rewrapTechnicalCredentials()
              .flatMap(response => Ok(response.asJson))
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

  private def decodeListSessionsQuery(
      request: Request[IO]
  ): IO[ListAdminSessionsQuery] = {
    val params = request.uri.query.params

    for {
      page <- parsePageParam(params.get("page"))
      pageSize <- parsePageSizeParam(params.get("pageSize"))
      _ <- validatePagingWindow(page, pageSize)
      userId <- parseOptionalUuidParam(params.get("userId"), "userId")
      teamId <- parseOptionalUuidParam(params.get("teamId"), "teamId")
      databaseId <- parseOptionalUuidParam(
        params.get("databaseId"),
        "databaseId"
      )
      state <- parseStateParam(params.get("state"))
      startedFrom <- parseOptionalInstantParam(
        params.get("startedFrom"),
        "startedFrom"
      )
      startedTo <- parseOptionalInstantParam(
        params.get("startedTo"),
        "startedTo"
      )
    } yield ListAdminSessionsQuery(
      page = page,
      pageSize = pageSize,
      userId = userId,
      teamId = teamId,
      databaseId = databaseId,
      state = state,
      startedFrom = startedFrom,
      startedTo = startedTo
    )
  }

  private def validatePagingWindow(page: Int, pageSize: Int): IO[Unit] = {
    val offsetLong = (page.toLong - 1L) * pageSize.toLong

    if offsetLong <= Int.MaxValue.toLong then IO.unit
    else
      IO.raiseError(
        ServiceError.BadRequest("page is too large for the requested pageSize")
      )
  }

  private def parsePageParam(raw: Option[String]): IO[Int] =
    parseOptionalIntParam(raw, "page").flatMap {
      case None                      => IO.pure(1)
      case Some(value) if value >= 1 => IO.pure(value)
      case Some(_)                   =>
        IO.raiseError(
          ServiceError.BadRequest("page must be greater than or equal to 1")
        )
    }

  private def parsePageSizeParam(raw: Option[String]): IO[Int] =
    parseOptionalIntParam(raw, "pageSize").flatMap {
      case None                                      => IO.pure(25)
      case Some(value) if value >= 1 && value <= 100 => IO.pure(value)
      case Some(_)                                   =>
        IO.raiseError(
          ServiceError.BadRequest("pageSize must be between 1 and 100")
        )
    }

  private def parseOptionalUuidParam(
      raw: Option[String],
      fieldName: String
  ): IO[Option[UUID]] =
    raw.traverse(parseUuid(_, fieldName))

  private def parseOptionalIntParam(
      raw: Option[String],
      fieldName: String
  ): IO[Option[Int]] =
    raw.traverse { value =>
      Either
        .catchOnly[NumberFormatException](value.toInt)
        .leftMap(_ =>
          ServiceError.BadRequest(s"Invalid integer for $fieldName")
        )
        .liftTo[IO]
    }

  private def parseOptionalInstantParam(
      raw: Option[String],
      fieldName: String
  ): IO[Option[Instant]] =
    raw.traverse { value =>
      Either
        .catchNonFatal(Instant.parse(value))
        .leftMap(_ =>
          ServiceError.BadRequest(s"Invalid instant for $fieldName")
        )
        .liftTo[IO]
    }

  private def parseStateParam(
      raw: Option[String]
  ): IO[AdminDatabaseSessionState] =
    raw match {
      case None        => IO.pure(AdminDatabaseSessionState.default)
      case Some(value) =>
        AdminDatabaseSessionState
          .fromString(value)
          .liftTo[IO](
            ServiceError.BadRequest("Invalid state")
          )
    }
}
