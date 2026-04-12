package dbwatchdog.support

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObject, Payload}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.circe.Json
import io.circe.syntax.*
import org.http4s.server.AuthMiddleware
import org.http4s.{Response, Status}

import dbwatchdog.auth.AuthUser
import dbwatchdog.config.AppConfig
import dbwatchdog.domain.User

object AuthTestSupport {
  val keyId = "test-key"
  val issuer = "https://issuer.example.test/realms/db-watchdog"
  val audience = "db-watchdog-backend"
  lazy val rsaJwk = new RSAKeyGenerator(2048).keyID(keyId).generate()

  val authUser: AuthUser = AuthUser(
    sub = "kc-123",
    email = "john@example.com",
    firstName = "John",
    lastName = "Doe",
    username = "john.doe",
    team = "backend",
    roles = Set("DBA", "user")
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
    AuthMiddleware.noSpider(
      Kleisli(_ => OptionT.some[IO](user)),
      _ => IO.pure(Response[IO](status = Status.Unauthorized))
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

  def signedJwt(
      payload: Json,
      kid: String = keyId
  ): String = {
    val header = JWSHeader.Builder(JWSAlgorithm.RS256)
      .keyID(kid)
      .build()
    val jwsObject = JWSObject(header, Payload(payload.noSpaces))
    jwsObject.sign(RSASSASigner(rsaJwk.toPrivateKey))
    jwsObject.serialize()
  }

  def validJwtPayload(
      now: Instant = Instant.now(),
      issuerOverride: String = issuer,
      audienceOverride: String = audience
  ): Json = Json.obj(
    "iss" -> Json.fromString(issuerOverride),
    "aud" -> Json.arr(Json.fromString(audienceOverride)),
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

  def keycloakConfig(jwksUrl: String): AppConfig.KeycloakConfig =
    AppConfig.KeycloakConfig(
      issuer = issuer,
      jwksUrl = jwksUrl,
      audience = audience,
      clockSkewSeconds = 30
    )
}
