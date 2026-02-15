package dbwatchdog

import cats.effect.IO
import cats.effect.kernel.Resource
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

import dbwatchdog.config.AppConfig
import dbwatchdog.service.HealthService

object Server {
  def make(using config: AppConfig): Resource[IO, Server] = {
    val healthRoutes = HealthService.routes

    EmberServerBuilder
      .default[IO]
      .withHost(config.server.hostIp4s)
      .withPort(config.server.portIp4s)
      .withHttpApp(healthRoutes.orNotFound)
      .build
  }
}
