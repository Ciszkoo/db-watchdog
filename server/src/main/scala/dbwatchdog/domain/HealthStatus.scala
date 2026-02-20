package dbwatchdog.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}

final case class HealthStatus(
    status: String,
    version: String,
    timestamp: Long
)

object HealthStatus {
  given encoder: Encoder[HealthStatus] = deriveEncoder[HealthStatus]
  given decoder: Decoder[HealthStatus] = deriveDecoder[HealthStatus]
}
