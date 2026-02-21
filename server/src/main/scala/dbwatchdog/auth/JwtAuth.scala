package dbwatchdog.auth

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.effect.kernel.Async
import io.circe.parser.decode
import io.circe.{Decoder, HCursor}
import org.http4s.Request
import org.http4s.headers.Authorization
import org.http4s.server.AuthMiddleware
import pdi.jwt.{JwtCirce, JwtOptions}

import dbwatchdog.config.AppConfig.KeycloakConfig

final case class AuthUser(
    sub: String, // Keycloak user ID
    email: String,
    firstName: String,
    lastName: String,
    username: String,
    team: Option[String],
    roles: Set[String]
)

object AuthUser {

  given decoder: Decoder[AuthUser] = (c: HCursor) =>
    for {
      sub <- c.get[String]("sub")
      email <- c.get[Option[String]]("email").map(_.getOrElse(""))
      firstName <- c.get[Option[String]]("given_name").map(_.getOrElse(""))
      lastName <- c.get[Option[String]]("family_name").map(_.getOrElse(""))
      username <- c
        .get[Option[String]]("preferred_username")
        .map(_.getOrElse(""))
      team <- c.get[Option[String]]("team")
      realmAccess <- c
        .downField("realm_access")
        .get[Option[List[String]]]("roles")
    } yield AuthUser(
      sub = sub,
      email = email,
      firstName = firstName,
      lastName = lastName,
      username = username,
      team = team,
      roles = realmAccess.getOrElse(Nil).toSet
    )
}

object JwtAuth {

  def middleware(config: KeycloakConfig): AuthMiddleware[IO, AuthUser] =
    AuthMiddleware.withFallThrough(
      Kleisli { request =>
        OptionT(extractAndValidateToken(request, config))
      }
    )

  private def extractAndValidateToken(
      request: Request[IO],
      config: KeycloakConfig
  ): IO[Option[AuthUser]] = {
    val maybeToken = request.headers
      .get[Authorization]
      .flatMap { auth =>
        val prefix = "Bearer "
        val credentials = auth.credentials.toString
        if credentials.startsWith(prefix) then
          Some(credentials.stripPrefix(prefix))
        else None
      }

    maybeToken match
      case Some(token) => validateToken(token, config)
      case None        => Async[IO].pure(None)

  }

  private def validateToken(
      token: String,
      config: KeycloakConfig
  ): IO[Option[AuthUser]] = {
    Async[IO]
      .delay {
        // Temporary no signature validation for dev simplicity
        val options =
          JwtOptions(signature = false, expiration = true, notBefore = true)

        JwtCirce
          .decodeJson(token, options)
          .toOption
          .flatMap { json =>
            decode[AuthUser](json.noSpaces).toOption
          }
      }
      .handleErrorWith { error =>
        Async[IO].pure(None)
      }
  }
}
