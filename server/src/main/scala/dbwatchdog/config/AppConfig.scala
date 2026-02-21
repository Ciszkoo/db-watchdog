package dbwatchdog.config

import com.comcast.ip4s.{Host, Port}
import pureconfig.{ConfigReader, ConfigSource}

case class AppConfig(
    server: AppConfig.ServerConfig,
    db: AppConfig.DatabaseConfig,
    keycloak: AppConfig.KeycloakConfig
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

  case class DatabaseConfig(
      host: String,
      port: Int,
      user: String,
      password: String,
      schema: String,
      threadPoolSize: Int
  ) {
    def url = s"jdbc:postgresql://$host:$port/$schema"
  }

  case class KeycloakConfig()
}
