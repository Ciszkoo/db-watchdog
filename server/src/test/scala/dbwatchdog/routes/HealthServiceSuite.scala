package dbwatchdog.routes

import cats.effect.IO
import org.http4s.Method
import org.http4s.implicits.*
import org.http4s.{Request, Status}
import org.http4s.circe.CirceEntityDecoder.*
import weaver.SimpleIOSuite

import dbwatchdog.domain.HealthStatus
import dbwatchdog.service.HealthService

object HealthServiceSuite extends SimpleIOSuite {

  test("GET /health returns an OK health payload") {
    for {
      response <- HealthService.routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health")
      )
      body <- response.as[HealthStatus]
    } yield expect(response.status == Status.Ok) and
      expect(body.status == "OK") and
      expect(body.version == "0.1.0-SNAPSHOT") and
      expect(body.timestamp > 0L)
  }
}
