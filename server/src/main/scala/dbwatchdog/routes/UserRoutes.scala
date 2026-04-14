package dbwatchdog.routes

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.server.AuthMiddleware
import org.http4s.{AuthedRoutes, HttpRoutes}

import dbwatchdog.auth.AuthUser
import dbwatchdog.service.UserService

object UserRoutes {
  def authedRoutes(
      userService: UserService
  ): AuthedRoutes[AuthUser, IO] =
    AuthedRoutes.of[AuthUser, IO] {
      case POST -> Root / "users" / "me" / "sync" as authUser =>
        val res = for {
          user <- userService.syncUser(authUser.toSyncInput)
          response <- Ok(user.asJson)
        } yield response

        res.handleErrorWith { case error =>
          InternalServerError(s"Error: ${error.getMessage}")
        }
    }

  def routes(
      userService: UserService
  )(using authMiddleware: AuthMiddleware[IO, AuthUser]): HttpRoutes[IO] =
    authMiddleware(authedRoutes(userService))
}
