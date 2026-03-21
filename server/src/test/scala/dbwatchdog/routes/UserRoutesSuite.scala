package dbwatchdog.routes

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.Method
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.http4s.{Request, Status}
import weaver.SimpleIOSuite

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.{SyncUserRequest, UpsertUserInput}
import dbwatchdog.service.UserService
import dbwatchdog.support.AuthTestSupport

object UserRoutesSuite extends SimpleIOSuite {
  private given AuthMiddleware[IO, AuthUser] =
    AuthTestSupport.staticAuthMiddleware()

  test("sync endpoint forwards a valid payload to the service") {
    val requestBody = SyncUserRequest(
      keycloakId = "kc-123",
      email = "john@example.com",
      firstName = "John",
      lastName = "Doe",
      team = "backend"
    )

    for {
      calls <- Ref.of[IO, Vector[UpsertUserInput]](Vector.empty)
      service = recordingService(calls)
      response <- UserRoutes
        .routes(service)
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/users/sync")
            .withEntity(requestBody.asJson)
        )
      payloads <- calls.get
    } yield expect(response.status == Status.Ok) and
      expect(payloads == Vector(requestBody.toUpsertInput))
  }

  test("sync endpoint rejects an invalid payload without calling the service") {
    for {
      calls <- Ref.of[IO, Vector[UpsertUserInput]](Vector.empty)
      service = recordingService(calls)
      response <- UserRoutes
        .routes(service)
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/users/sync")
            .withEntity(
              Json.obj("email" -> Json.fromString("missing-fields")).asJson
            )
        )
      payloads <- calls.get
    } yield expect(
      response.status.code >= 400 && response.status.code < 500
    ) and
      expect(payloads.isEmpty)
  }

  test("sync endpoint returns 500 when the service fails") {
    val failingService = new UserService {
      def syncUser(input: UpsertUserInput) =
        IO.raiseError(new RuntimeException("boom"))

      def getUserByKeycloakId(keycloackId: String) =
        IO.pure(AuthTestSupport.persistedUser)
    }

    val requestBody = SyncUserRequest(
      keycloakId = "kc-123",
      email = "john@example.com",
      firstName = "John",
      lastName = "Doe",
      team = "backend"
    )

    for {
      response <- UserRoutes
        .routes(failingService)
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/users/sync")
            .withEntity(requestBody.asJson)
        )
      body <- response.as[String]
    } yield expect(response.status == Status.InternalServerError) and
      expect(body.contains("boom"))
  }

  private def recordingService(
      calls: Ref[IO, Vector[UpsertUserInput]]
  ): UserService = new UserService {
    def syncUser(input: UpsertUserInput) =
      calls.update(_ :+ input) *> IO.pure(AuthTestSupport.persistedUser)

    def getUserByKeycloakId(keycloackId: String) =
      IO.pure(AuthTestSupport.persistedUser)
  }
}
