package dbwatchdog

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest}
import java.time.Duration

import scala.concurrent.duration.*

import cats.effect.IO
import doobie.implicits.*

import dbwatchdog.service.UserService
import dbwatchdog.support.PostgresIntegrationSuite

object BootstrapIntegrationSuite extends PostgresIntegrationSuite {
  test("AppResources.make creates a usable transactor-backed database") { db =>
    given dbwatchdog.config.AppConfig = db.config

    AppResources.make.use { resources =>
      for {
        result <- resources.db.transact(sql"select 1".query[Int].unique)
      } yield expect(result == 1)
    }
  }

  test("Server.make starts an HTTP server that serves health") { db =>
    val port = freePort()
    given dbwatchdog.config.AppConfig = db.config.copy(
      server = dbwatchdog.config.AppConfig.ServerConfig("127.0.0.1", port)
    )

    val services = dbwatchdog.service.Services(
      users = new UserService {
        def syncUser(input: dbwatchdog.domain.UpsertUserInput) =
          IO.raiseError(new IllegalStateException("not used"))

        def getUserByKeycloakId(keycloackId: String) =
          IO.raiseError(new IllegalStateException("not used"))
      }
    )

    Server.make(services).use { _ =>
      requestUntilOk(port).map(response => expect(response.contains("\"OK\"")))
    }
  }

  test("Main.run boots the application from loaded config") { db =>
    val port = freePort()
    val config = db.config.copy(
      server = dbwatchdog.config.AppConfig.ServerConfig("127.0.0.1", port)
    )

    for {
      _ <- IO.blocking(setMainAppConfig(config))
      fiber <- Main.run(Nil).start
      response <- requestUntilOk(port)
      _ <- fiber.cancel
      outcome <- fiber.join
    } yield expect(response.contains("\"OK\"")) and
      expect(outcome.isCanceled)
  }

  private def requestUntilOk(
      port: Int,
      attempts: Int = 30
  ): IO[String] = {
    val client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"http://127.0.0.1:$port/api/v1/health"))
      .timeout(Duration.ofSeconds(1))
      .GET()
      .build()

    def loop(remaining: Int): IO[String] =
      IO.blocking(
        client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
      ).map(_.body())
        .handleErrorWith { error =>
          if remaining <= 1 then IO.raiseError(error)
          else IO.sleep(200.millis) *> loop(remaining - 1)
        }

    loop(attempts)
  }

  private def freePort(): Int = {
    val socket = ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  private def setMainAppConfig(config: dbwatchdog.config.AppConfig): Unit = {
    val field = classOf[dbwatchdog.Main$].getDeclaredField("appConfig$lzy1")
    field.setAccessible(true)
    field.set(dbwatchdog.Main, config)
  }
}
