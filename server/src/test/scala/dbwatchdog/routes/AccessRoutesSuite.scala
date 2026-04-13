package dbwatchdog.routes

import java.time.Instant
import java.util.UUID

import cats.effect.{IO, Ref}
import io.circe.syntax.*
import org.http4s.Method
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.http4s.{Request, Status}
import weaver.SimpleIOSuite

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.{DatabaseResponse, EffectiveDatabaseAccessResponse, IssueOtpResponse}
import dbwatchdog.service.AccessService
import dbwatchdog.support.AuthTestSupport

object AccessRoutesSuite extends SimpleIOSuite {
  private given AuthMiddleware[IO, AuthUser] =
    AuthTestSupport.staticAuthMiddleware(AuthTestSupport.regularAuthUser)

  private val databaseId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")

  test("GET /me/effective-access delegates to the access service with the authenticated user") {
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

  test("POST /me/databases/{databaseId}/otp parses the UUID and returns the OTP without otpHash") {
    for {
      calls <- Ref.of[IO, Vector[(AuthUser, UUID)]](Vector.empty)
      response <- AccessRoutes
        .routes(recordingAccessService(issueOtpCalls = calls))
        .orNotFound
        .run(Request[IO](Method.POST, uri"/me/databases/dddddddd-dddd-dddd-dddd-dddddddddddd/otp"))
      observedCalls <- calls.get
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(
        observedCalls == Vector(AuthTestSupport.regularAuthUser -> databaseId)
      ) and
      expect(body.contains("temporary-otp")) and
      expect(!body.contains("otpHash"))
  }

  private def recordingAccessService(
      effectiveAccessCalls: Ref[IO, Vector[AuthUser]] =
        Ref.unsafe[IO, Vector[AuthUser]](Vector.empty),
      issueOtpCalls: Ref[IO, Vector[(AuthUser, UUID)]] =
        Ref.unsafe[IO, Vector[(AuthUser, UUID)]](Vector.empty)
  ): AccessService = new AccessService {
    def getEffectiveAccessForUser(userId: UUID) =
      IO.pure(List.empty[EffectiveDatabaseAccessResponse])

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
            updatedAt = Instant.parse("2026-01-01T00:00:00Z")
          )
        )
      )
  }
}
