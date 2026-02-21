package dbwatchdog

import cats.effect.{ExitCode, IO, IOApp}

import dbwatchdog.config.AppConfig
import dbwatchdog.database.Migration
import dbwatchdog.repository.Repositories
import dbwatchdog.service.Services

object Main extends IOApp {
  given appConfig: AppConfig = AppConfig.load

  def run(args: List[String]): IO[ExitCode] = runWithConfig

  def runWithConfig(using config: AppConfig): IO[ExitCode] = {
    val app = for {
      resources <- AppResources.make
      repos = Repositories.make
      services = Services.make(repos, resources.db)
      _ <- Server.make(services)
    } yield ()

    for {
      _ <- Migration.migrate
      exitCode <- app.use { _ =>
        IO.println(
          s"Starting server on ${config.server.host}:${config.server.port}"
        ) >> IO.never.as(ExitCode.Success)
      }
    } yield exitCode

  }
}
