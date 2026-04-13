package dbwatchdog.routes

import java.time.Instant
import java.util.UUID

import cats.effect.{IO, Ref}
import io.circe.Json
import org.http4s.Method
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.http4s.{Request, Status}
import weaver.SimpleIOSuite

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.{
  AdminUserResponse,
  CreateDatabaseRequest,
  DatabaseResponse,
  TeamResponse,
  UpsertTeamDatabaseGrantRequest,
  UpsertUserDatabaseAccessExtensionRequest
}
import dbwatchdog.service.{AdminService, ServiceError}
import dbwatchdog.support.AuthTestSupport

object AdminRoutesSuite extends SimpleIOSuite {
  private val createdAt = Instant.parse("2024-01-01T00:00:00Z")
  private val updatedAt = Instant.parse("2024-01-02T00:00:00Z")

  test("admin routes return 403 for authenticated non-DBA callers") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware(AuthTestSupport.regularAuthUser)

    for {
      calls <- Ref.of[IO, Int](0)
      response <- AdminRoutes
        .routes(recordingAdminService(calls))
        .orNotFound
        .run(Request[IO](Method.GET, uri"/admin/teams"))
      observedCalls <- calls.get
    } yield expect(response.status == Status.Forbidden) and
      expect(observedCalls == 0)
  }

  test("DBA callers can create databases and responses do not expose technicalPassword") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val requestBody = Json.obj(
      "engine" -> Json.fromString("postgres"),
      "host" -> Json.fromString("db.internal"),
      "port" -> Json.fromInt(5432),
      "technicalUser" -> Json.fromString("technical_user"),
      "technicalPassword" -> Json.fromString("super-secret"),
      "databaseName" -> Json.fromString("analytics")
    )

    for {
      calls <- Ref.of[IO, Vector[CreateDatabaseRequest]](Vector.empty)
      response <- AdminRoutes
        .routes(recordingAdminService(createCalls = calls))
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/admin/databases").withEntity(requestBody)
        )
      body <- response.bodyText.compile.string
      observedCalls <- calls.get
    } yield expect(response.status == Status.Ok) and
      expect(
        observedCalls == Vector(
          CreateDatabaseRequest(
            engine = "postgres",
            host = "db.internal",
            port = 5432,
            technicalUser = "technical_user",
            technicalPassword = "super-secret",
            databaseName = "analytics"
          )
        )
      ) and
      expect(body.contains("technicalUser")) and
      expect(!body.contains("technicalPassword"))
  }

  test("admin database creation returns 400 for malformed JSON") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    for {
      response <- AdminRoutes
        .routes(recordingAdminService())
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/admin/databases")
            .withEntity("""{"engine":"postgres"""")
        )
    } yield expect(response.status == Status.BadRequest)
  }

  test("admin routes return 400 for invalid UUID path params") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    for {
      response <- AdminRoutes
        .routes(recordingAdminService())
        .orNotFound
        .run(
          Request[IO](
            Method.DELETE,
            uri"/admin/team-database-grants/not-a-uuid/cccccccc-cccc-cccc-cccc-cccccccccccc"
          )
        )
    } yield expect(response.status == Status.BadRequest)
  }

  test("admin routes return 404 when the service reports a missing entity") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val service = new AdminService {
      def listTeams() = IO.pure(List.empty[TeamResponse])
      def listUsers() = IO.pure(List.empty[AdminUserResponse])
      def listDatabases() = IO.pure(List.empty[DatabaseResponse])
      def createDatabase(request: CreateDatabaseRequest) =
        IO.raiseError(ServiceError.NotFound("database missing"))
      def upsertTeamDatabaseGrant(request: UpsertTeamDatabaseGrantRequest) =
        IO.unit
      def deleteTeamDatabaseGrant(teamId: UUID, databaseId: UUID) =
        IO.unit
      def upsertUserDatabaseAccessExtension(
          request: UpsertUserDatabaseAccessExtensionRequest
      ) = IO.unit
      def deleteUserDatabaseAccessExtension(userId: UUID, databaseId: UUID) =
        IO.raiseError(ServiceError.NotFound("extension missing"))
    }

    for {
      response <- AdminRoutes
        .routes(service)
        .orNotFound
        .run(
          Request[IO](
            Method.DELETE,
            uri"/admin/user-database-access-extensions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          )
        )
    } yield expect(response.status == Status.NotFound)
  }

  private def recordingAdminService(
      teamCalls: Ref[IO, Int] = Ref.unsafe[IO, Int](0),
      createCalls: Ref[IO, Vector[CreateDatabaseRequest]] =
        Ref.unsafe[IO, Vector[CreateDatabaseRequest]](Vector.empty)
  ): AdminService = new AdminService {
    def listTeams() =
      teamCalls.update(_ + 1) *> IO.pure(
        List(
          TeamResponse(
            id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            name = "backend",
            createdAt = createdAt,
            updatedAt = updatedAt
          )
        )
      )

    def listUsers() = IO.pure(List.empty[AdminUserResponse])

    def listDatabases() =
      IO.pure(
        List(
          DatabaseResponse(
            id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            engine = "postgres",
            host = "db.internal",
            port = 5432,
            technicalUser = "technical_user",
            databaseName = "analytics",
            createdAt = createdAt,
            updatedAt = updatedAt
          )
        )
      )

    def createDatabase(request: CreateDatabaseRequest) =
      createCalls.update(_ :+ request) *> IO.pure(
        DatabaseResponse(
          id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
          engine = request.engine,
          host = request.host,
          port = request.port,
          technicalUser = request.technicalUser,
          databaseName = request.databaseName,
          createdAt = createdAt,
          updatedAt = updatedAt
        )
      )

    def upsertTeamDatabaseGrant(request: UpsertTeamDatabaseGrantRequest) =
      IO.unit

    def deleteTeamDatabaseGrant(teamId: UUID, databaseId: UUID) =
      IO.unit

    def upsertUserDatabaseAccessExtension(
        request: UpsertUserDatabaseAccessExtensionRequest
    ) = IO.unit

    def deleteUserDatabaseAccessExtension(
        userId: UUID,
        databaseId: UUID
    ) = IO.unit
  }
}
