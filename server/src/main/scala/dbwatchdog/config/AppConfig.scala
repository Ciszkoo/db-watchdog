package dbwatchdog.config

import com.comcast.ip4s.{Host, Port}
import pureconfig.{ConfigReader, ConfigSource}

case class AppConfig(
    server: AppConfig.ServerConfig
) derives ConfigReader

object AppConfig {
  def load: AppConfig = ConfigSource.default.loadOrThrow[AppConfig]

  case class ServerConfig(
      host: String,
      port: Int
  ) {
    def hostIp4s: Host = Host.fromString(host).get
    def portIp4s: Port = Port.fromInt(port).get
  }
}
