package dbwatchdog.routes

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.server.AuthMiddleware
import org.http4s.{AuthedRoutes, HttpRoutes}

import dbwatchdog.auth.AuthUser
import dbwatchdog.service.AccessService

object AccessRoutes {
  def routes(
      accessService: AccessService
  )(using authMiddleware: AuthMiddleware[IO, AuthUser]): HttpRoutes[IO] = {
    val authedRoutes = AuthedRoutes.of[AuthUser, IO] {
      case GET -> Root / "me" / "effective-access" as authUser =>
        handleServiceErrors {
          accessService
            .getEffectiveAccessForAuthenticatedUser(authUser)
            .flatMap(access => Ok(access.asJson))
        }

      case POST -> Root / "me" / "databases" / databaseId / "otp" as authUser =>
        handleServiceErrors {
          for {
            parsedDatabaseId <- parseUuid(databaseId, "databaseId")
            otp <- accessService.issueOtp(authUser, parsedDatabaseId)
            response <- Ok(otp.asJson)
          } yield response
        }

      case GET -> Root / "admin" / "users" / userId / "effective-access" as authUser =>
        dbaOnly(authUser) {
          handleServiceErrors {
            for {
              parsedUserId <- parseUuid(userId, "userId")
              access <- accessService.getEffectiveAccessForUser(parsedUserId)
              response <- Ok(access.asJson)
            } yield response
          }
        }
    }

    authMiddleware(authedRoutes)
  }
}
