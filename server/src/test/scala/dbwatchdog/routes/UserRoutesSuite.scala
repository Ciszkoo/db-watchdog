package dbwatchdog.routes

import cats.effect.{IO, Ref}
import org.http4s.Method
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.http4s.{Request, Status}
import weaver.SimpleIOSuite

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.AuthenticatedUserSyncInput
import dbwatchdog.service.UserService
import dbwatchdog.support.AuthTestSupport

object UserRoutesSuite extends SimpleIOSuite {
  private given AuthMiddleware[IO, AuthUser] =
    AuthTestSupport.staticAuthMiddleware()

  test("sync endpoint derives the authenticated user from the token context") {
    for {
      calls <- Ref.of[IO, Vector[AuthenticatedUserSyncInput]](Vector.empty)
      service = recordingService(calls)
      response <- UserRoutes
        .routes(service)
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/users/me/sync")
        )
      payloads <- calls.get
    } yield expect(response.status == Status.Ok) and
      expect(
        payloads == Vector(
          AuthenticatedUserSyncInput(
            keycloakId = AuthTestSupport.authUser.sub,
            email = AuthTestSupport.authUser.email,
            firstName = AuthTestSupport.authUser.firstName,
            lastName = AuthTestSupport.authUser.lastName,
            team = AuthTestSupport.authUser.team
          )
        )
      )
  }

  test("legacy sync endpoint path is not exposed anymore") {
    for {
      response <- UserRoutes
        .routes(
          recordingService(
            Ref.unsafe[IO, Vector[AuthenticatedUserSyncInput]](Vector.empty)
          )
        )
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/users/sync")
        )
    } yield expect(response.status == Status.NotFound)
  }

  test("sync endpoint returns 500 when the service fails") {
    val failingService = new UserService {
      def syncUser(input: AuthenticatedUserSyncInput) =
        IO.raiseError(new RuntimeException("boom"))

      def getUserByKeycloakId(keycloackId: String) =
        IO.pure(AuthTestSupport.persistedUser)
    }

    for {
      response <- UserRoutes
        .routes(failingService)
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/users/me/sync")
        )
      body <- response.as[String]
    } yield expect(response.status == Status.InternalServerError) and
      expect(body.contains("boom"))
  }

  private def recordingService(
      calls: Ref[IO, Vector[AuthenticatedUserSyncInput]]
  ): UserService = new UserService {
    def syncUser(input: AuthenticatedUserSyncInput) =
      calls.update(_ :+ input) *> IO.pure(AuthTestSupport.persistedUser)

    def getUserByKeycloakId(keycloackId: String) =
      IO.pure(AuthTestSupport.persistedUser)
  }
}
