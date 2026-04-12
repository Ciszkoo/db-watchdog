package dbwatchdog.auth

import java.net.InetSocketAddress

import cats.effect.{IO, Resource}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.sun.net.httpserver.HttpServer
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
import weaver.IOSuite

import dbwatchdog.config.AppConfig
import dbwatchdog.support.AuthTestSupport

object JwtAuthSuite extends IOSuite {
  type Res = AppConfig.KeycloakConfig

  override def sharedResource: Resource[IO, AppConfig.KeycloakConfig] =
    Resource.make {
      IO.blocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
          "/jwks",
          exchange => {
            val body = s"""{"keys":[${AuthTestSupport.rsaJwk.toPublicJWK.toJSONString}]}"""
            val bytes = body.getBytes()
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.length)
            val output = exchange.getResponseBody
            output.write(bytes)
            output.close()
            exchange.close()
          }
        )
        server.start()
        server
      }
    } { server =>
      IO.blocking(server.stop(0))
    }.map { server =>
      AuthTestSupport.keycloakConfig(
        s"http://127.0.0.1:${server.getAddress.getPort}/jwks"
      )
    }

  private def securedApp(using
      middleware: AuthMiddleware[IO, AuthUser]
  ) =
    middleware(
      AuthedRoutes.of[AuthUser, IO] { case GET -> Root / "secured" as user =>
        Ok(s"${user.username}:${user.roles.toList.sorted.mkString(",")}:${user.isDba}")
      }
    ).orNotFound

  test("authenticates a request with a valid signed bearer token") { config =>
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(config)

    val token = AuthTestSupport.signedJwt(AuthTestSupport.validJwtPayload())
    val request = Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, token))
      )

    for {
      response <- securedApp.run(request)
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(body == s"${AuthTestSupport.authUser.username}:DBA,user:true")
  }

  test("returns unauthorized when the authorization header is missing") {
    config =>
      given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(config)

      for {
        response <- securedApp.run(
          Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
        )
      } yield expect(response.status == Status.Unauthorized)
  }

  test("returns unauthorized when the token is malformed") { config =>
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(config)

    val request = Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, "not-a-jwt"))
      )

    for {
      response <- securedApp.run(request)
    } yield expect(response.status == Status.Unauthorized)
  }

  test("rejects unsigned tokens") { config =>
    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.unsignedJwt(AuthTestSupport.validJwtPayload()),
        config
      )
    } yield expect(result.isEmpty)
  }

  test("rejects tokens signed with the wrong key") { config =>
    val wrongKey = new RSAKeyGenerator(2048).keyID(AuthTestSupport.keyId).generate()
    val badToken = {
      val payload = AuthTestSupport.validJwtPayload()
      val header = new com.nimbusds.jose.JWSHeader.Builder(
        com.nimbusds.jose.JWSAlgorithm.RS256
      ).keyID(AuthTestSupport.keyId).build()
      val jwsObject = new com.nimbusds.jose.JWSObject(
        header,
        new com.nimbusds.jose.Payload(payload.noSpaces)
      )
      jwsObject.sign(new com.nimbusds.jose.crypto.RSASSASigner(wrongKey.toPrivateKey))
      jwsObject.serialize()
    }

    for {
      result <- JwtAuth.decodeToken(badToken, config)
    } yield expect(result.isEmpty)
  }

  test("rejects tokens with the wrong issuer") { config =>
    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(
          AuthTestSupport.validJwtPayload(
            issuerOverride = "https://issuer.example.test/realms/other"
          )
        ),
        config
      )
    } yield expect(result.isEmpty)
  }

  test("rejects tokens with the wrong audience") { config =>
    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(
          AuthTestSupport.validJwtPayload(audienceOverride = "other-audience")
        ),
        config
      )
    } yield expect(result.isEmpty)
  }

  test("rejects tokens missing the team claim") { config =>
    val payload = AuthTestSupport
      .validJwtPayload()
      .mapObject(_.remove("team"))

    for {
      result <- JwtAuth.decodeToken(AuthTestSupport.signedJwt(payload), config)
    } yield expect(result.isEmpty)
  }

  test("decodes the DBA role from JWT claims") { config =>
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(config)

    val payload = AuthTestSupport
      .validJwtPayload()
      .deepMerge(
        Json.obj(
          "realm_access" -> Json.obj(
            "roles" -> Json.arr(
              Json.fromString("DBA"),
              Json.fromString("user")
            )
          )
        )
      )

    val token = AuthTestSupport.signedJwt(payload)
    val request = Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, token))
      )

    for {
      response <- securedApp.run(request)
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(body.endsWith(":true"))
  }
}
