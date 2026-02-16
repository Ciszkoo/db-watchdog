package dbwatchdog.domain

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.*

final case class HealthStatus(
    status: String,
    version: String,
    timestamp: Long
)

object HealthStatus {
  given encoder: Encoder[HealthStatus] = deriveEncoder[HealthStatus]
  given decoder: Decoder[HealthStatus] = deriveDecoder[HealthStatus]
}
