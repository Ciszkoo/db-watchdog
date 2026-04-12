package dbwatchdog.auth

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import scala.jdk.CollectionConverters.*

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.effect.kernel.Async
import com.nimbusds.jose.crypto.{ECDSAVerifier, RSASSAVerifier}
import com.nimbusds.jose.jwk.{ECKey, JWKSet, RSAKey}
import com.nimbusds.jwt.SignedJWT
import io.circe.parser.decode
import io.circe.{Decoder, HCursor}
import org.http4s.headers.Authorization
import org.http4s.server.AuthMiddleware
import org.http4s.{AuthScheme, Credentials, Request, Response, Status}

import dbwatchdog.config.AppConfig.KeycloakConfig

final case class AuthUser(
    sub: String, // Keycloak user ID
    email: String,
    firstName: String,
    lastName: String,
    username: String,
    team: String,
    roles: Set[String]
) {
  def isDba: Boolean = roles.contains("DBA")
}

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
      team <- c.get[String]("team")
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
  private val httpClient = HttpClient.newHttpClient()

  def middleware(config: KeycloakConfig): AuthMiddleware[IO, AuthUser] =
    AuthMiddleware.noSpider(
      Kleisli { request =>
        OptionT(extractAndValidateToken(request, config))
      },
      _ => IO.pure(Response[IO](status = Status.Unauthorized))
    )

  private def extractAndValidateToken(
      request: Request[IO],
      config: KeycloakConfig
  ): IO[Option[AuthUser]] =
    request.headers.get[Authorization].map(_.credentials) match
      case Some(Credentials.Token(AuthScheme.Bearer, token)) =>
        decodeToken(token, config)
      case _ =>
        Async[IO].pure(None)

  private[auth] def decodeToken(
      token: String,
      config: KeycloakConfig
  ): IO[Option[AuthUser]] =
    Async[IO]
      .blocking {
        val signedJwt = SignedJWT.parse(token)

        validateHeader(signedJwt)
        validateSignature(signedJwt, config)
        validateClaims(signedJwt, config)

        decode[AuthUser](signedJwt.getPayload.toString).toOption
      }
      .attempt
      .map(_.toOption.flatten)

  private def validateHeader(jwt: SignedJWT): Unit = {
    val header = jwt.getHeader

    if header == null || header.getAlgorithm == null then
      throw IllegalArgumentException("Token header is missing algorithm")

    if header.getAlgorithm.getName.equalsIgnoreCase("none") then
      throw IllegalArgumentException("Unsigned tokens are not accepted")

    if Option(header.getKeyID).forall(_.trim.isEmpty) then
      throw IllegalArgumentException("Token header is missing kid")
  }

  private def validateSignature(
      jwt: SignedJWT,
      config: KeycloakConfig
  ): Unit = {
    val jwkSet = loadJwkSet(config)
    val kid = jwt.getHeader.getKeyID
    val jwk = Option(jwkSet.getKeyByKeyId(kid))
      .getOrElse(throw IllegalArgumentException("Unable to resolve JWK"))

    val verified = jwk match
      case rsaKey: RSAKey =>
        jwt.verify(RSASSAVerifier(rsaKey.toRSAPublicKey))
      case ecKey: ECKey =>
        jwt.verify(ECDSAVerifier(ecKey.toECPublicKey))
      case _ =>
        throw IllegalArgumentException("Unsupported JWK type")

    if !verified then
      throw IllegalArgumentException("JWT signature verification failed")
  }

  private def validateClaims(jwt: SignedJWT, config: KeycloakConfig): Unit = {
    val claims = jwt.getJWTClaimsSet
    val now = Instant.now()
    val clockSkew = config.clockSkewSeconds

    if claims == null then
      throw IllegalArgumentException("Token is missing claims")

    if claims.getIssuer != config.issuer then
      throw IllegalArgumentException("JWT issuer mismatch")

    val audience =
      Option(claims.getAudience).map(_.asScala.toSet).getOrElse(Set.empty)
    val audienceMatches = audience.contains(config.audience)
    val authorizedPartyMatches =
      Option(claims.getStringClaim("azp")).contains(config.authorizedParty)
    if !audienceMatches && !authorizedPartyMatches then
      throw IllegalArgumentException("JWT audience and azp mismatch")

    val expiresAt = Option(claims.getExpirationTime)
      .map(_.toInstant)
      .getOrElse(throw IllegalArgumentException("JWT is missing exp"))
    if expiresAt.isBefore(now.minusSeconds(clockSkew)) then
      throw IllegalArgumentException("JWT is expired")

    Option(claims.getNotBeforeTime).map(_.toInstant).foreach { notBefore =>
      if notBefore.isAfter(now.plusSeconds(clockSkew)) then
        throw IllegalArgumentException("JWT is not yet valid")
    }
  }

  private def loadJwkSet(config: KeycloakConfig): JWKSet = {
    val request =
      HttpRequest.newBuilder(URI.create(config.jwksUrl)).GET().build()
    val response =
      httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if response.statusCode() != 200 then
      throw IllegalArgumentException("Unable to fetch JWKS")

    JWKSet.parse(response.body())
  }
}
