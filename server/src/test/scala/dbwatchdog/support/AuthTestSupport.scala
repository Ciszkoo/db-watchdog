package dbwatchdog.support

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.server.AuthMiddleware

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.User

object AuthTestSupport {
  val authUser: AuthUser = AuthUser(
    sub = "kc-123",
    email = "john@example.com",
    firstName = "John",
    lastName = "Doe",
    username = "john.doe",
    team = Some("backend"),
    roles = Set("user", "admin")
  )

  val persistedUser: User = User(
    id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
    keycloakId = authUser.sub,
    email = authUser.email,
    firstName = authUser.firstName,
    lastName = authUser.lastName,
    teamId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-01T00:00:00Z")
  )

  def staticAuthMiddleware(
      user: AuthUser = authUser
  ): AuthMiddleware[IO, AuthUser] =
    AuthMiddleware.withFallThrough(
      Kleisli(_ => OptionT.some[IO](user))
    )

  def unsignedJwt(
      payload: Json
  ): String = {
    val header = Json.obj(
      "alg" -> Json.fromString("none"),
      "typ" -> Json.fromString("JWT")
    )

    s"${base64Url(header.noSpaces)}.${base64Url(payload.noSpaces)}."
  }

  private def base64Url(value: String): String =
    Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(value.getBytes(StandardCharsets.UTF_8))

  def validJwtPayload(now: Instant = Instant.now()): Json = Json.obj(
    "sub" -> Json.fromString(authUser.sub),
    "email" -> Json.fromString(authUser.email),
    "given_name" -> Json.fromString(authUser.firstName),
    "family_name" -> Json.fromString(authUser.lastName),
    "preferred_username" -> Json.fromString(authUser.username),
    "team" -> authUser.team.asJson,
    "realm_access" -> Json.obj(
      "roles" -> authUser.roles.toList.sorted.asJson
    ),
    "exp" -> Json.fromLong(now.plusSeconds(3600).getEpochSecond),
    "nbf" -> Json.fromLong(now.minusSeconds(60).getEpochSecond)
  )
}
