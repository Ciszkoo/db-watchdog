package dbwatchdog.routes

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.dsl.io.*
import org.http4s.server.AuthMiddleware
import org.http4s.{AuthedRoutes, HttpRoutes}

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.SyncUserRequest
import dbwatchdog.service.UserService

object UserRoutes {

  def routes(
      userService: UserService
  )(using authMiddleware: AuthMiddleware[IO, AuthUser]): HttpRoutes[IO] = {

    val authedRoutes = AuthedRoutes.of[AuthUser, IO] {
      case req @ POST -> Root / "users" / "sync" as authUser => {
        val res = for {
          syncReq <- req.req.as[SyncUserRequest]
          user <- userService.syncUser(
            syncReq.keycloakId,
            syncReq.email,
            syncReq.firstName,
            syncReq.lastName,
            syncReq.team
          )
          response <- Ok(user.asJson)
        } yield response

        res.handleErrorWith { error =>
          IO.println(s"Error: $error") >>
            InternalServerError(s"Error: ${error.getMessage}")
        }
      }
    }

    authMiddleware(authedRoutes)
  }
}
