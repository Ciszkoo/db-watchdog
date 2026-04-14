package dbwatchdog.routes

import java.time.Instant
import java.util.UUID

import cats.effect.{IO, Ref}
import org.http4s.Method
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.http4s.{Request, Status}
import weaver.SimpleIOSuite

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.{
  DatabaseResponse,
  EffectiveDatabaseAccessResponse,
  IssueOtpResponse
}
import dbwatchdog.service.{AccessService, ServiceError}
import dbwatchdog.support.AuthTestSupport

object AccessRoutesSuite extends SimpleIOSuite {
  private val databaseId =
    UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")

  test(
    "GET /me/effective-access delegates to the access service with the authenticated user"
  ) {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware(AuthTestSupport.regularAuthUser)

    for {
      calls <- Ref.of[IO, Vector[AuthUser]](Vector.empty)
      response <- AccessRoutes
        .routes(recordingAccessService(effectiveAccessCalls = calls))
        .orNotFound
        .run(Request[IO](Method.GET, uri"/me/effective-access"))
      observedCalls <- calls.get
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(observedCalls == Vector(AuthTestSupport.regularAuthUser)) and
      expect(body.contains("TEAM"))
  }

  test(
    "POST /me/databases/{databaseId}/otp parses the UUID and returns the OTP without otpHash"
  ) {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware(AuthTestSupport.regularAuthUser)

    for {
      calls <- Ref.of[IO, Vector[(AuthUser, UUID)]](Vector.empty)
      response <- AccessRoutes
        .routes(recordingAccessService(issueOtpCalls = calls))
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/me/databases/dddddddd-dddd-dddd-dddd-dddddddddddd/otp"
          )
        )
      observedCalls <- calls.get
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(
        observedCalls == Vector(AuthTestSupport.regularAuthUser -> databaseId)
      ) and
      expect(body.contains("temporary-otp")) and
      expect(!body.contains("otpHash"))
  }

  test(
    "GET /admin/users/{userId}/effective-access delegates to the access service for DBA callers"
  ) {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    val requestedUserId =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    for {
      calls <- Ref.of[IO, Vector[UUID]](Vector.empty)
      response <- AccessRoutes
        .routes(recordingAccessService(adminEffectiveAccessCalls = calls))
        .orNotFound
        .run(
          Request[IO](
            Method.GET,
            uri"/admin/users/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/effective-access"
          )
        )
      observedCalls <- calls.get
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(observedCalls == Vector(requestedUserId)) and
      expect(body.contains("TEAM"))
  }

  test(
    "GET /admin/users/{userId}/effective-access returns 403 for non-DBA callers"
  ) {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware(AuthTestSupport.regularAuthUser)

    for {
      calls <- Ref.of[IO, Vector[UUID]](Vector.empty)
      response <- AccessRoutes
        .routes(recordingAccessService(adminEffectiveAccessCalls = calls))
        .orNotFound
        .run(
          Request[IO](
            Method.GET,
            uri"/admin/users/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/effective-access"
          )
        )
      observedCalls <- calls.get
    } yield expect(response.status == Status.Forbidden) and
      expect(observedCalls.isEmpty)
  }

  test("access routes return 400 for invalid UUID path params") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware()

    for {
      otpResponse <- AccessRoutes
        .routes(recordingAccessService())
        .orNotFound
        .run(Request[IO](Method.POST, uri"/me/databases/not-a-uuid/otp"))
      adminResponse <- AccessRoutes
        .routes(recordingAccessService())
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/admin/users/not-a-uuid/effective-access")
        )
    } yield expect(otpResponse.status == Status.BadRequest) and
      expect(adminResponse.status == Status.BadRequest)
  }

  test("access routes return 404 when the service reports a missing entity") {
    given AuthMiddleware[IO, AuthUser] =
      AuthTestSupport.staticAuthMiddleware(AuthTestSupport.regularAuthUser)

    val service = new AccessService {
      def getEffectiveAccessForUser(userId: UUID) =
        IO.raiseError(ServiceError.NotFound("user missing"))

      def getEffectiveAccessForAuthenticatedUser(authUser: AuthUser) =
        IO.pure(List.empty[EffectiveDatabaseAccessResponse])

      def issueOtp(authUser: AuthUser, databaseId: UUID) =
        IO.raiseError(ServiceError.NotFound("database missing"))
    }

    for {
      response <- AccessRoutes
        .routes(service)
        .orNotFound
        .run(
          Request[IO](
            Method.POST,
            uri"/me/databases/dddddddd-dddd-dddd-dddd-dddddddddddd/otp"
          )
        )
    } yield expect(response.status == Status.NotFound)
  }

  private def recordingAccessService(
      effectiveAccessCalls: Ref[IO, Vector[AuthUser]] =
        Ref.unsafe[IO, Vector[AuthUser]](Vector.empty),
      adminEffectiveAccessCalls: Ref[IO, Vector[UUID]] =
        Ref.unsafe[IO, Vector[UUID]](Vector.empty),
      issueOtpCalls: Ref[IO, Vector[(AuthUser, UUID)]] =
        Ref.unsafe[IO, Vector[(AuthUser, UUID)]](Vector.empty)
  ): AccessService = new AccessService {
    def getEffectiveAccessForUser(userId: UUID) =
      adminEffectiveAccessCalls.update(_ :+ userId) *> IO.pure(
        List(
          EffectiveDatabaseAccessResponse(
            databaseId = databaseId,
            engine = "postgres",
            host = "db.internal",
            port = 5432,
            databaseName = "analytics",
            loginIdentifier = "admin@example.com",
            accessSource = "TEAM",
            extensionExpiresAt = None
          )
        )
      )

    def getEffectiveAccessForAuthenticatedUser(authUser: AuthUser) =
      effectiveAccessCalls.update(_ :+ authUser) *> IO.pure(
        List(
          EffectiveDatabaseAccessResponse(
            databaseId = databaseId,
            engine = "postgres",
            host = "db.internal",
            port = 5432,
            databaseName = "analytics",
            loginIdentifier = authUser.email,
            accessSource = "TEAM",
            extensionExpiresAt = None
          )
        )
      )

    def issueOtp(authUser: AuthUser, requestedDatabaseId: UUID) =
      issueOtpCalls.update(_ :+ (authUser -> requestedDatabaseId)) *> IO.pure(
        IssueOtpResponse(
          credentialId =
            UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
          otp = "temporary-otp",
          expiresAt = Instant.parse("2026-01-01T00:05:00Z"),
          database = DatabaseResponse(
            id = requestedDatabaseId,
            engine = "postgres",
            host = "db.internal",
            port = 5432,
            technicalUser = "technical_user",
            databaseName = "analytics",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deactivatedAt = None,
            isActive = true
          )
        )
      )
  }
}
