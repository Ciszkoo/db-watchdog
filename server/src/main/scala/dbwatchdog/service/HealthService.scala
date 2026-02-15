package dbwatchdog.service
import cats.effect.IO
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import dbwatchdog.domain.HealthStatus

object HealthService {
  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      val healthStatus = HealthStatus(
        status = "OK",
        version = "0.1.0-SNAPSHOT",
        timestamp = System.currentTimeMillis()
      )
      Ok(healthStatus)
  }
}
