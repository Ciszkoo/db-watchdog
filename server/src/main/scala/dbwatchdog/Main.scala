package dbwatchdog

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp

import dbwatchdog.config.AppConfig

object Main extends IOApp {
  given appConfig: AppConfig = AppConfig.load

  def run(args: List[String]): IO[ExitCode] = runWithConfig

  def runWithConfig(using config: AppConfig): IO[ExitCode] = {
    Server.make.use { _ =>
      for {
        _ <- IO.println(
          s"Starting server on ${config.server.host}:${config.server.port}"
        )
        // Server will run until interrupted
        _ <- IO.never
      } yield ExitCode.Success
    }
  }
}
