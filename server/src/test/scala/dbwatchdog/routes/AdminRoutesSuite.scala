package dbwatchdog.routes

import java.time.Instant
import java.util.UUID

import cats.data.{Kleisli, OptionT}
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
  AdminDatabaseSessionResponse,
  AdminTeamDatabaseGrantResponse,
  AdminUserDatabaseAccessExtensionResponse,
  AdminUserResponse,
  CreateDatabaseRequest,
  DatabaseResponse,
  TeamResponse,
  TechnicalCredentialRewrapResponse,
  UpdateDatabaseRequest,
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
        .routes(recordingAdminService(sessionCalls = calls))
        .orNotFound
        .run(Request[IO](Method.GET, uri"/admin/sessions"))
      observedCalls <- calls.get
    } yield expect(response.status == Status.Forbidden) and
      expect(observedCalls == 0)
  }

  test("new admin read routes return 403 for authenticated non-DBA callers") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware(AuthTestSupport.regularAuthUser)

    for {
      teamGrantCalls <- Ref.of[IO, Int](0)
      extensionCalls <- Ref.of[IO, Int](0)
      teamGrantResponse <- AdminRoutes
        .routes(
          recordingAdminService(
            teamGrantCalls = teamGrantCalls,
            extensionCalls = extensionCalls
          )
        )
        .orNotFound
        .run(Request[IO](Method.GET, uri"/admin/team-database-grants"))
      extensionResponse <- AdminRoutes
        .routes(
          recordingAdminService(
            teamGrantCalls = teamGrantCalls,
            extensionCalls = extensionCalls
          )
        )
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/admin/user-database-access-extensions")
        )
      observedTeamGrantCalls <- teamGrantCalls.get
      observedExtensionCalls <- extensionCalls.get
    } yield expect(teamGrantResponse.status == Status.Forbidden) and
      expect(extensionResponse.status == Status.Forbidden) and
      expect(observedTeamGrantCalls == 0) and
      expect(observedExtensionCalls == 0)
  }

  test(
    "new admin database lifecycle routes return 403 for authenticated non-DBA callers"
  ) {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware(AuthTestSupport.regularAuthUser)

    for {
      updateCalls <- Ref.of[IO, Vector[(UUID, UpdateDatabaseRequest)]](
        Vector.empty
      )
      deactivateCalls <- Ref.of[IO, Vector[UUID]](Vector.empty)
      reactivateCalls <- Ref.of[IO, Vector[UUID]](Vector.empty)
      rewrapCalls <- Ref.of[IO, Int](0)
      updateResponse <- AdminRoutes
        .routes(
          recordingAdminService(
            updateCalls = updateCalls,
            deactivateCalls = deactivateCalls,
            reactivateCalls = reactivateCalls,
            rewrapCalls = rewrapCalls
          )
        )
        .orNotFound
        .run(
          Request[IO](
            Method.PUT,
            uri"/admin/databases/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          ).withEntity(
            Json.obj(
              "engine" -> Json.fromString("postgres"),
              "host" -> Json.fromString("db.internal"),
              "port" -> Json.fromInt(5432),
              "technicalUser" -> Json.fromString("technical_user"),
              "technicalPassword" -> Json.Null,
              "databaseName" -> Json.fromString("analytics")
            )
          )
        )
      deactivateResponse <- AdminRoutes
        .routes(
          recordingAdminService(
            updateCalls = updateCalls,
            deactivateCalls = deactivateCalls,
            reactivateCalls = reactivateCalls,
            rewrapCalls = rewrapCalls
          )
        )
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/admin/databases/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/deactivate"
          )
        )
      reactivateResponse <- AdminRoutes
        .routes(
          recordingAdminService(
            updateCalls = updateCalls,
            deactivateCalls = deactivateCalls,
            reactivateCalls = reactivateCalls,
            rewrapCalls = rewrapCalls
          )
        )
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/admin/databases/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/reactivate"
          )
        )
      rewrapResponse <- AdminRoutes
        .routes(
          recordingAdminService(
            updateCalls = updateCalls,
            deactivateCalls = deactivateCalls,
            reactivateCalls = reactivateCalls,
            rewrapCalls = rewrapCalls
          )
        )
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/admin/databases/technical-credentials/rewrap"
          )
        )
      observedUpdateCalls <- updateCalls.get
      observedDeactivateCalls <- deactivateCalls.get
      observedReactivateCalls <- reactivateCalls.get
      observedRewrapCalls <- rewrapCalls.get
    } yield expect(updateResponse.status == Status.Forbidden) and
      expect(deactivateResponse.status == Status.Forbidden) and
      expect(reactivateResponse.status == Status.Forbidden) and
      expect(rewrapResponse.status == Status.Forbidden) and
      expect(observedUpdateCalls.isEmpty) and
      expect(observedDeactivateCalls.isEmpty) and
      expect(observedReactivateCalls.isEmpty) and
      expect(observedRewrapCalls == 0)
  }

  test("DBA callers can list recorded sessions") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    for {
      response <- AdminRoutes
        .routes(recordingAdminService())
        .orNotFound
        .run(Request[IO](Method.GET, uri"/admin/sessions"))
      body <- response.bodyText.compile.string
    } yield expect(response.status == Status.Ok) and
      expect(body.contains("credentialId")) and
      expect(body.contains("clientAddr"))
  }

  test("DBA callers can list current team database grants") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    for {
      response <- AdminRoutes
        .routes(recordingAdminService())
        .orNotFound
        .run(Request[IO](Method.GET, uri"/admin/team-database-grants"))
      body <- response.bodyText.compile.string
    } yield expect(response.status == Status.Ok) and
      expect(body.contains("teamId")) and
      expect(body.contains("databaseId"))
  }

  test("DBA callers can list current user database access extensions") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    for {
      response <- AdminRoutes
        .routes(recordingAdminService())
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/admin/user-database-access-extensions")
        )
      body <- response.bodyText.compile.string
    } yield expect(response.status == Status.Ok) and
      expect(body.contains("userId")) and
      expect(body.contains("expiresAt"))
  }

  test(
    "admin session route returns 401 when auth middleware rejects the request"
  ) {
    given AuthMiddleware[IO, AuthUser] =
      AuthMiddleware.noSpider(
        Kleisli(_ => OptionT.none[IO, AuthUser]),
        _ => IO.pure(org.http4s.Response[IO](status = Status.Unauthorized))
      )

    for {
      response <- AdminRoutes
        .routes(recordingAdminService())
        .orNotFound
        .run(Request[IO](Method.GET, uri"/admin/sessions"))
    } yield expect(response.status == Status.Unauthorized)
  }

  test(
    "DBA callers can create databases and responses do not expose technicalPassword"
  ) {
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
          Request[IO](Method.POST, uri"/admin/databases")
            .withEntity(requestBody)
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

  test(
    "DBA callers can update databases and nullable technicalPassword keeps JSON handling explicit"
  ) {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val databaseId =
      UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    val requestBody = Json.obj(
      "engine" -> Json.fromString("postgres"),
      "host" -> Json.fromString("edited.internal"),
      "port" -> Json.fromInt(6432),
      "technicalUser" -> Json.fromString("rotated_user"),
      "technicalPassword" -> Json.Null,
      "databaseName" -> Json.fromString("analytics_reporting")
    )

    for {
      calls <- Ref.of[IO, Vector[(UUID, UpdateDatabaseRequest)]](Vector.empty)
      response <- AdminRoutes
        .routes(recordingAdminService(updateCalls = calls))
        .orNotFound
        .run(
          Request[IO](
            Method.PUT,
            uri"/admin/databases/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          )
            .withEntity(requestBody)
        )
      body <- response.bodyText.compile.string
      observedCalls <- calls.get
    } yield expect(response.status == Status.Ok) and
      expect(
        observedCalls == Vector(
          databaseId -> UpdateDatabaseRequest(
            engine = "postgres",
            host = "edited.internal",
            port = 6432,
            technicalUser = "rotated_user",
            technicalPassword = None,
            databaseName = "analytics_reporting"
          )
        )
      ) and
      expect(body.contains("\"isActive\":true")) and
      expect(!body.contains("technicalPassword"))
  }

  test("DBA callers can deactivate and reactivate databases") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val databaseId =
      UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    for {
      deactivateCalls <- Ref.of[IO, Vector[UUID]](Vector.empty)
      reactivateCalls <- Ref.of[IO, Vector[UUID]](Vector.empty)
      deactivateResponse <- AdminRoutes
        .routes(
          recordingAdminService(
            deactivateCalls = deactivateCalls,
            reactivateCalls = reactivateCalls
          )
        )
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/admin/databases/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/deactivate"
          )
        )
      deactivateBody <- deactivateResponse.bodyText.compile.string
      reactivateResponse <- AdminRoutes
        .routes(
          recordingAdminService(
            deactivateCalls = deactivateCalls,
            reactivateCalls = reactivateCalls
          )
        )
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/admin/databases/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/reactivate"
          )
        )
      reactivateBody <- reactivateResponse.bodyText.compile.string
      observedDeactivateCalls <- deactivateCalls.get
      observedReactivateCalls <- reactivateCalls.get
    } yield expect(deactivateResponse.status == Status.Ok) and
      expect(reactivateResponse.status == Status.Ok) and
      expect(observedDeactivateCalls == Vector(databaseId)) and
      expect(observedReactivateCalls == Vector(databaseId)) and
      expect(deactivateBody.contains("\"isActive\":false")) and
      expect(reactivateBody.contains("\"isActive\":true"))
  }

  test("DBA callers can trigger technical credential rewrap") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    for {
      rewrapCalls <- Ref.of[IO, Int](0)
      response <- AdminRoutes
        .routes(recordingAdminService(rewrapCalls = rewrapCalls))
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/admin/databases/technical-credentials/rewrap"
          )
        )
      body <- response.bodyText.compile.string
      observedRewrapCalls <- rewrapCalls.get
    } yield expect(response.status == Status.Ok) and
      expect(body.contains("\"rotatedDatabaseCount\":3")) and
      expect(observedRewrapCalls == 1)
  }

  test("technical credential rewrap maps service conflicts to 409") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val service = new AdminService {
      def listTeams() = IO.pure(List.empty[TeamResponse])
      def listUsers() = IO.pure(List.empty[AdminUserResponse])
      def listSessions() = IO.pure(List.empty[AdminDatabaseSessionResponse])
      def listDatabases() = IO.pure(List.empty[DatabaseResponse])
      def listTeamDatabaseGrants() =
        IO.pure(List.empty[AdminTeamDatabaseGrantResponse])
      def listUserDatabaseAccessExtensions() =
        IO.pure(List.empty[AdminUserDatabaseAccessExtensionResponse])
      def createDatabase(request: CreateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = UUID.randomUUID()))
      def updateDatabase(databaseId: UUID, request: UpdateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def deactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId, isActive = false))
      def reactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def rewrapTechnicalCredentials() =
        IO.raiseError(
          ServiceError.Conflict(
            "Technical credential rewrap requires a distinct TECHNICAL_CREDENTIALS_PREVIOUS_KEY"
          )
        )
      def upsertTeamDatabaseGrant(request: UpsertTeamDatabaseGrantRequest) =
        IO.unit
      def deleteTeamDatabaseGrant(teamId: UUID, databaseId: UUID) =
        IO.unit
      def upsertUserDatabaseAccessExtension(
          request: UpsertUserDatabaseAccessExtensionRequest
      ) = IO.unit
      def deleteUserDatabaseAccessExtension(userId: UUID, databaseId: UUID) =
        IO.unit
    }

    for {
      response <- AdminRoutes
        .routes(service)
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/admin/databases/technical-credentials/rewrap"
          )
        )
    } yield expect(response.status == Status.Conflict)
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
      def listSessions() = IO.pure(List.empty[AdminDatabaseSessionResponse])
      def listDatabases() = IO.pure(List.empty[DatabaseResponse])
      def listTeamDatabaseGrants() =
        IO.pure(List.empty[AdminTeamDatabaseGrantResponse])
      def listUserDatabaseAccessExtensions() =
        IO.pure(List.empty[AdminUserDatabaseAccessExtensionResponse])
      def createDatabase(request: CreateDatabaseRequest) =
        IO.raiseError(ServiceError.NotFound("database missing"))
      def updateDatabase(databaseId: UUID, request: UpdateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def deactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId, isActive = false))
      def reactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def rewrapTechnicalCredentials() =
        IO.pure(TechnicalCredentialRewrapResponse(0))
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

  test("team grant list route maps service errors") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val service = new AdminService {
      def listTeams() = IO.pure(List.empty[TeamResponse])
      def listUsers() = IO.pure(List.empty[AdminUserResponse])
      def listSessions() = IO.pure(List.empty[AdminDatabaseSessionResponse])
      def listDatabases() = IO.pure(List.empty[DatabaseResponse])
      def listTeamDatabaseGrants() =
        IO.raiseError(ServiceError.BadRequest("invalid grant state"))
      def listUserDatabaseAccessExtensions() =
        IO.pure(List.empty[AdminUserDatabaseAccessExtensionResponse])
      def createDatabase(request: CreateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = UUID.randomUUID()))
      def updateDatabase(databaseId: UUID, request: UpdateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def deactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId, isActive = false))
      def reactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def rewrapTechnicalCredentials() =
        IO.pure(TechnicalCredentialRewrapResponse(0))
      def upsertTeamDatabaseGrant(request: UpsertTeamDatabaseGrantRequest) =
        IO.unit
      def deleteTeamDatabaseGrant(teamId: UUID, databaseId: UUID) =
        IO.unit
      def upsertUserDatabaseAccessExtension(
          request: UpsertUserDatabaseAccessExtensionRequest
      ) = IO.unit
      def deleteUserDatabaseAccessExtension(userId: UUID, databaseId: UUID) =
        IO.unit
    }

    for {
      response <- AdminRoutes
        .routes(service)
        .orNotFound
        .run(Request[IO](Method.GET, uri"/admin/team-database-grants"))
    } yield expect(response.status == Status.BadRequest)
  }

  test("admin routes return 409 when the service reports conflict") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val service = new AdminService {
      def listTeams() = IO.pure(List.empty[TeamResponse])
      def listUsers() = IO.pure(List.empty[AdminUserResponse])
      def listSessions() = IO.pure(List.empty[AdminDatabaseSessionResponse])
      def listDatabases() = IO.pure(List.empty[DatabaseResponse])
      def listTeamDatabaseGrants() =
        IO.pure(List.empty[AdminTeamDatabaseGrantResponse])
      def listUserDatabaseAccessExtensions() =
        IO.pure(List.empty[AdminUserDatabaseAccessExtensionResponse])
      def createDatabase(request: CreateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = UUID.randomUUID()))
      def updateDatabase(databaseId: UUID, request: UpdateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def deactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId, isActive = false))
      def reactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def rewrapTechnicalCredentials() =
        IO.raiseError(ServiceError.Conflict("rewrap misconfigured"))
      def upsertTeamDatabaseGrant(request: UpsertTeamDatabaseGrantRequest) =
        IO.raiseError(ServiceError.Conflict("database inactive"))
      def deleteTeamDatabaseGrant(teamId: UUID, databaseId: UUID) =
        IO.unit
      def upsertUserDatabaseAccessExtension(
          request: UpsertUserDatabaseAccessExtensionRequest
      ) = IO.unit
      def deleteUserDatabaseAccessExtension(userId: UUID, databaseId: UUID) =
        IO.unit
    }

    for {
      response <- AdminRoutes
        .routes(service)
        .orNotFound
        .run(
          Request[IO](Method.PUT, uri"/admin/team-database-grants")
            .withEntity(
              Json.obj(
                "teamId" ->
                  Json.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "databaseId" ->
                  Json.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
              )
            )
        )
    } yield expect(response.status == Status.Conflict)
  }

  test("user extension list route maps service errors") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val service = new AdminService {
      def listTeams() = IO.pure(List.empty[TeamResponse])
      def listUsers() = IO.pure(List.empty[AdminUserResponse])
      def listSessions() = IO.pure(List.empty[AdminDatabaseSessionResponse])
      def listDatabases() = IO.pure(List.empty[DatabaseResponse])
      def listTeamDatabaseGrants() =
        IO.pure(List.empty[AdminTeamDatabaseGrantResponse])
      def listUserDatabaseAccessExtensions() =
        IO.raiseError(ServiceError.NotFound("extensions missing"))
      def createDatabase(request: CreateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = UUID.randomUUID()))
      def updateDatabase(databaseId: UUID, request: UpdateDatabaseRequest) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def deactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId, isActive = false))
      def reactivateDatabase(databaseId: UUID) =
        IO.pure(sampleDatabaseResponse(id = databaseId))
      def rewrapTechnicalCredentials() =
        IO.pure(TechnicalCredentialRewrapResponse(0))
      def upsertTeamDatabaseGrant(request: UpsertTeamDatabaseGrantRequest) =
        IO.unit
      def deleteTeamDatabaseGrant(teamId: UUID, databaseId: UUID) =
        IO.unit
      def upsertUserDatabaseAccessExtension(
          request: UpsertUserDatabaseAccessExtensionRequest
      ) = IO.unit
      def deleteUserDatabaseAccessExtension(userId: UUID, databaseId: UUID) =
        IO.unit
    }

    for {
      response <- AdminRoutes
        .routes(service)
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/admin/user-database-access-extensions")
        )
    } yield expect(response.status == Status.NotFound)
  }

  private def recordingAdminService(
      teamCalls: Ref[IO, Int] = Ref.unsafe[IO, Int](0),
      sessionCalls: Ref[IO, Int] = Ref.unsafe[IO, Int](0),
      teamGrantCalls: Ref[IO, Int] = Ref.unsafe[IO, Int](0),
      extensionCalls: Ref[IO, Int] = Ref.unsafe[IO, Int](0),
      createCalls: Ref[IO, Vector[CreateDatabaseRequest]] =
        Ref.unsafe[IO, Vector[CreateDatabaseRequest]](Vector.empty),
      updateCalls: Ref[IO, Vector[(UUID, UpdateDatabaseRequest)]] =
        Ref.unsafe[IO, Vector[(UUID, UpdateDatabaseRequest)]](Vector.empty),
      deactivateCalls: Ref[IO, Vector[UUID]] =
        Ref.unsafe[IO, Vector[UUID]](Vector.empty),
      reactivateCalls: Ref[IO, Vector[UUID]] =
        Ref.unsafe[IO, Vector[UUID]](Vector.empty),
      rewrapCalls: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
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

    def listSessions() =
      sessionCalls.update(_ + 1) *> IO.pure(
        List(
          AdminDatabaseSessionResponse(
            id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
            credentialId =
              UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
            clientAddr = "127.0.0.1:5432",
            startedAt = createdAt,
            endedAt = Some(updatedAt),
            bytesSent = Some(120L),
            bytesReceived = Some(240L),
            user = AdminUserResponse(
              id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
              keycloakId = "kc-123",
              email = "john@example.com",
              firstName = "John",
              lastName = "Doe",
              team = TeamResponse(
                id = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                name = "backend",
                createdAt = createdAt,
                updatedAt = updatedAt
              ),
              createdAt = createdAt,
              updatedAt = updatedAt
            ),
            database = sampleDatabaseResponse(
              id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            )
          )
        )
      )

    def listDatabases() =
      IO.pure(
        List(
          sampleDatabaseResponse(
            id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
          )
        )
      )

    def listTeamDatabaseGrants() =
      teamGrantCalls.update(_ + 1) *> IO.pure(
        List(
          AdminTeamDatabaseGrantResponse(
            id = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444"),
            teamId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            databaseId =
              UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            createdAt = createdAt,
            updatedAt = updatedAt
          )
        )
      )

    def listUserDatabaseAccessExtensions() =
      extensionCalls.update(_ + 1) *> IO.pure(
        List(
          AdminUserDatabaseAccessExtensionResponse(
            id = UUID.fromString("cccccccc-1111-2222-3333-444444444444"),
            userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            databaseId =
              UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            expiresAt = Some(Instant.parse("2024-02-01T12:00:00Z")),
            createdAt = createdAt,
            updatedAt = updatedAt
          )
        )
      )

    def createDatabase(request: CreateDatabaseRequest) =
      createCalls.update(_ :+ request) *> IO.pure(
        sampleDatabaseResponse(
          id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
          engine = request.engine,
          host = request.host,
          port = request.port,
          technicalUser = request.technicalUser,
          databaseName = request.databaseName
        )
      )

    def updateDatabase(databaseId: UUID, request: UpdateDatabaseRequest) =
      updateCalls.update(_ :+ (databaseId -> request)) *> IO.pure(
        sampleDatabaseResponse(
          id = databaseId,
          engine = request.engine,
          host = request.host,
          port = request.port,
          technicalUser = request.technicalUser,
          databaseName = request.databaseName
        )
      )

    def deactivateDatabase(databaseId: UUID) =
      deactivateCalls.update(_ :+ databaseId) *> IO.pure(
        sampleDatabaseResponse(id = databaseId, isActive = false)
      )

    def reactivateDatabase(databaseId: UUID) =
      reactivateCalls.update(_ :+ databaseId) *> IO.pure(
        sampleDatabaseResponse(id = databaseId)
      )

    def rewrapTechnicalCredentials() =
      rewrapCalls.update(_ + 1) *> IO.pure(
        TechnicalCredentialRewrapResponse(3)
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

  private def sampleDatabaseResponse(
      id: UUID,
      engine: String = "postgres",
      host: String = "db.internal",
      port: Int = 5432,
      technicalUser: String = "technical_user",
      databaseName: String = "analytics",
      isActive: Boolean = true
  ): DatabaseResponse =
    DatabaseResponse(
      id = id,
      engine = engine,
      host = host,
      port = port,
      technicalUser = technicalUser,
      databaseName = databaseName,
      createdAt = createdAt,
      updatedAt = updatedAt,
      deactivatedAt =
        if isActive then None else Some(Instant.parse("2024-01-03T00:00:00Z")),
      isActive = isActive
    )
}
