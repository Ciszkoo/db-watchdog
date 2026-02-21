package dbwatchdog

import cats.effect.IO
import cats.effect.kernel.Resource
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.server.{AuthMiddleware, Server}

import dbwatchdog.auth.{AuthUser, JwtAuth}
import dbwatchdog.config.AppConfig
import dbwatchdog.routes.Routes
import dbwatchdog.service.Services

object Server {
  def make(
      services: Services
  )(using config: AppConfig): Resource[IO, Server] = {
    given AuthMiddleware[IO, AuthUser] = JwtAuth.middleware(config.keycloak)

    val routes = Routes.all(
      userService = services.users
    )
    val corsRoutes = CORS.policy.withAllowOriginAll(routes)
    val httpApp = corsRoutes.orNotFound

    EmberServerBuilder
      .default[IO]
      .withHost(config.server.hostIp4s)
      .withPort(config.server.portIp4s)
      .withHttpApp(httpApp)
      .build
  }
}
