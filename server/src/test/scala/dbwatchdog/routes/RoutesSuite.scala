package dbwatchdog.routes

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.Method
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.http4s.{Request, Status}
import weaver.SimpleIOSuite

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.SyncUserRequest
import dbwatchdog.service.UserService
import dbwatchdog.support.AuthTestSupport

object RoutesSuite extends SimpleIOSuite {
  private val stubUserService: UserService = new UserService {
    def syncUser(input: dbwatchdog.domain.UpsertUserInput) =
      IO.pure(AuthTestSupport.persistedUser)

    def getUserByKeycloakId(keycloackId: String) =
      IO.pure(AuthTestSupport.persistedUser)
  }

  private given AuthMiddleware[IO, AuthUser] =
    AuthTestSupport.staticAuthMiddleware()

  test("mounts the health endpoint under /api/v1") {
    for {
      response <- Routes
        .all(stubUserService)
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/api/v1/health")
        )
    } yield expect(response.status == Status.Ok)
  }

  test("mounts the user sync endpoint under /api/v1") {
    val payload = SyncUserRequest(
      keycloakId = "kc-123",
      email = "john@example.com",
      firstName = "John",
      lastName = "Doe",
      team = "backend"
    )

    for {
      response <- Routes
        .all(stubUserService)
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/api/v1/users/sync")
            .withEntity(payload.asJson)
        )
    } yield expect(response.status == Status.Ok)
  }

  test("does not expose the health endpoint without the API prefix") {
    for {
      response <- Routes
        .all(stubUserService)
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/health")
        )
    } yield expect(response.status == Status.NotFound)
  }
}
