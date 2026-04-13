package dbwatchdog.auth

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

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
  final case class TestContext(
      config: AppConfig.KeycloakConfig,
      jwksRequests: AtomicInteger
  )

  type Res = TestContext

  override def sharedResource: Resource[IO, TestContext] =
    testContextResource

  private def testContextResource: Resource[IO, TestContext] =
    Resource
      .make {
        IO.blocking {
          val jwksRequests = AtomicInteger(0)
          val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
          server.createContext(
            "/jwks",
            exchange => {
              jwksRequests.incrementAndGet()
              val body =
                s"""{"keys":[${AuthTestSupport.rsaJwk.toPublicJWK.toJSONString}]}"""
              val bytes = body.getBytes()
              exchange.getResponseHeaders
                .add("Content-Type", "application/json")
              exchange.sendResponseHeaders(200, bytes.length)
              val output = exchange.getResponseBody
              output.write(bytes)
              output.close()
              exchange.close()
            }
          )
          server.start()
          (server, jwksRequests)
        }
      } { case (server, _) =>
        IO.blocking(server.stop(0))
      }
      .map { case (server, jwksRequests) =>
        TestContext(
          config = AuthTestSupport.keycloakConfig(
            s"http://127.0.0.1:${server.getAddress.getPort}/jwks"
          ),
          jwksRequests = jwksRequests
        )
      }

  private def securedApp(using
      middleware: AuthMiddleware[IO, AuthUser]
  ) =
    middleware(
      AuthedRoutes.of[AuthUser, IO] { case GET -> Root / "secured" as user =>
        Ok(
          s"${user.username}:${user.roles.toList.sorted.mkString(",")}:${user.isDba}"
        )
      }
    ).orNotFound

  private def requestWithToken(token: String): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      .putHeaders(
        Authorization(Credentials.Token(AuthScheme.Bearer, token))
      )

  test("authenticates a request with a valid signed bearer token") { ctx =>
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(ctx.config)

    val token = AuthTestSupport.signedJwt(AuthTestSupport.validJwtPayload())

    for {
      response <- securedApp.run(requestWithToken(token))
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(body == s"${AuthTestSupport.authUser.username}:DBA,user:true")
  }

  test("returns unauthorized when the authorization header is missing") { ctx =>
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(ctx.config)

    for {
      response <- securedApp.run(
        Request[IO](Method.GET, Uri.unsafeFromString("/secured"))
      )
    } yield expect(response.status == Status.Unauthorized)
  }

  test("returns unauthorized when the token is malformed") { ctx =>
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(ctx.config)

    for {
      response <- securedApp.run(requestWithToken("not-a-jwt"))
    } yield expect(response.status == Status.Unauthorized)
  }

  test("rejects unsigned tokens") { ctx =>
    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.unsignedJwt(AuthTestSupport.validJwtPayload()),
        ctx.config
      )
    } yield expect(result.isEmpty)
  }

  test("rejects tokens signed with the wrong key") { ctx =>
    val wrongKey =
      new RSAKeyGenerator(2048).keyID(AuthTestSupport.keyId).generate()
    val badToken = {
      val payload = AuthTestSupport.validJwtPayload()
      val header = new com.nimbusds.jose.JWSHeader.Builder(
        com.nimbusds.jose.JWSAlgorithm.RS256
      ).keyID(AuthTestSupport.keyId).build()
      val jwsObject = new com.nimbusds.jose.JWSObject(
        header,
        new com.nimbusds.jose.Payload(payload.noSpaces)
      )
      jwsObject.sign(
        new com.nimbusds.jose.crypto.RSASSASigner(wrongKey.toPrivateKey)
      )
      jwsObject.serialize()
    }

    for {
      result <- JwtAuth.decodeToken(badToken, ctx.config)
    } yield expect(result.isEmpty)
  }

  test("rejects tokens with the wrong issuer") { ctx =>
    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(
          AuthTestSupport.validJwtPayload(
            issuerOverride = "https://issuer.example.test/realms/other"
          )
        ),
        ctx.config
      )
    } yield expect(result.isEmpty)
  }

  test("rejects tokens with the wrong audience") { ctx =>
    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(
          AuthTestSupport.validJwtPayload(
            audienceOverride = "other-audience",
            authorizedPartyOverride = "other-client"
          )
        ),
        ctx.config
      )
    } yield expect(result.isEmpty)
  }

  test("accepts tokens with matching azp when audience is missing") { ctx =>
    val payload = AuthTestSupport
      .validJwtPayload()
      .mapObject(_.remove("aud"))

    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(payload),
        ctx.config
      )
    } yield expect(result.nonEmpty)
  }

  test("rejects tokens with the wrong audience even when azp matches") { ctx =>
    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(
          AuthTestSupport.validJwtPayload(audienceOverride = "other-audience")
        ),
        ctx.config
      )
    } yield expect(result.isEmpty)
  }

  test("accepts tokens with the correct audience even when azp is missing") {
    ctx =>
      val payload = AuthTestSupport
        .validJwtPayload()
        .mapObject(_.remove("azp"))

      for {
        result <- JwtAuth.decodeToken(
          AuthTestSupport.signedJwt(payload),
          ctx.config
        )
      } yield expect(result.nonEmpty)
  }

  test("accepts tokens when nbf is missing") { ctx =>
    val payload = AuthTestSupport
      .validJwtPayload()
      .mapObject(_.remove("nbf"))

    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(payload),
        ctx.config
      )
    } yield expect(result.nonEmpty)
  }

  test("rejects tokens when nbf is in the future") { ctx =>
    val now = java.time.Instant.now()
    val payload = AuthTestSupport.validJwtPayload(
      now = now,
      notBeforeEpochSecondOverride = Some(now.plusSeconds(120).getEpochSecond)
    )

    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(payload),
        ctx.config
      )
    } yield expect(result.isEmpty)
  }

  test("rejects tokens missing the team claim") { ctx =>
    val payload = AuthTestSupport
      .validJwtPayload()
      .mapObject(_.remove("team"))

    for {
      result <- JwtAuth.decodeToken(
        AuthTestSupport.signedJwt(payload),
        ctx.config
      )
    } yield expect(result.isEmpty)
  }

  test("reuses cached JWKS for repeated validation with the same middleware") {
    _ =>
      testContextResource.use { ctx =>
        given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(ctx.config)

        val token = AuthTestSupport.signedJwt(AuthTestSupport.validJwtPayload())

        for {
          first <- securedApp.run(requestWithToken(token))
          second <- securedApp.run(requestWithToken(token))
        } yield expect(first.status == Status.Ok) and
          expect(second.status == Status.Ok) and
          expect(ctx.jwksRequests.get() == 1)
      }
  }

  test("refreshes JWKS on unknown kid and still rejects the token cleanly") {
    _ =>
      testContextResource.use { ctx =>
        given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(ctx.config)

        val validToken =
          AuthTestSupport.signedJwt(AuthTestSupport.validJwtPayload())
        val unknownKidToken = AuthTestSupport.signedJwt(
          AuthTestSupport.validJwtPayload(),
          kid = "missing-kid"
        )

        for {
          first <- securedApp.run(requestWithToken(validToken))
          second <- securedApp.run(requestWithToken(unknownKidToken))
        } yield expect(first.status == Status.Ok) and
          expect(second.status == Status.Unauthorized) and
          expect(ctx.jwksRequests.get() == 2)
      }
  }

  test("decodes the DBA role from JWT claims") { ctx =>
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(ctx.config)

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

    for {
      response <- securedApp.run(requestWithToken(token))
      body <- response.as[String]
    } yield expect(response.status == Status.Ok) and
      expect(body.endsWith(":true"))
  }
}
