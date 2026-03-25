package dbwatchdog.auth

import cats.effect.IO
import io.circe.Json
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.server.AuthMiddleware
import org.http4s.{
  AuthScheme,
  AuthedRoutes,
  Credentials,
  Method,
  Request,
  Status,
  Uri
}
import weaver.SimpleIOSuite

import dbwatchdog.config.AppConfig
import dbwatchdog.support.AuthTestSupport

object JwtAuthSuite extends SimpleIOSuite {
  private given AppConfig.KeycloakConfig = AppConfig.KeycloakConfig()

  private def securedApp(using
      middleware: AuthMiddleware[IO, AuthUser]
  ) =
    middleware(
      AuthedRoutes.of[AuthUser, IO] { case GET -> Root / "secured" as user =>
        Ok(s"${user.username}:${user.roles.toList.sorted.mkString(",")}")
      }
    ).orNotFound

  test("authenticates a request with a valid bearer token") {
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(
      summon[AppConfig.KeycloakConfig]
    )

    val token = AuthTestSupport.unsignedJwt(AuthTestSupport.validJwtPayload())
    val request = Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, token))
      )

    for {
      response <- securedApp.run(request)
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(body == s"${AuthTestSupport.authUser.username}:admin,user")
  }

  test("falls through when the authorization header is missing") {
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(
      summon[AppConfig.KeycloakConfig]
    )

    for {
      response <- securedApp.run(
        Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      )
    } yield expect(response.status == Status.NotFound)
  }

  test("falls through when the token is malformed") {
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(
      summon[AppConfig.KeycloakConfig]
    )

    val request = Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, "not-a-jwt"))
      )

    for {
      response <- securedApp.run(request)
    } yield expect(response.status == Status.NotFound)
  }

  test("decodeToken returns None for invalid input") {
    for {
      result <- JwtAuth.decodeToken("not-a-jwt")
    } yield expect(result.isEmpty)
  }

  test("falls through when the authorization scheme is not Bearer") {
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(
      summon[AppConfig.KeycloakConfig]
    )

    val request = Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Basic, "abc123"))
      )

    for {
      response <- securedApp.run(request)
    } yield expect(response.status == Status.NotFound)
  }

  test("decodes the realm roles from JWT claims") {
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(
      summon[AppConfig.KeycloakConfig]
    )

    val payload = AuthTestSupport
      .validJwtPayload()
      .deepMerge(
        Json.obj(
          "realm_access" -> Json.obj(
            "roles" -> Json.arr(
              Json.fromString("auditor"),
              Json.fromString("user")
            )
          )
        )
      )

    val token = AuthTestSupport.unsignedJwt(payload)
    val request = Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, token))
      )

    for {
      response <- securedApp.run(request)
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(body == s"${AuthTestSupport.authUser.username}:auditor,user")
  }

}
