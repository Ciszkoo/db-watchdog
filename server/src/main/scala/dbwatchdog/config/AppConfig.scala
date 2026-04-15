package dbwatchdog.config

import com.comcast.ip4s.{Host, Port}
import pureconfig.{ConfigReader, ConfigSource}

case class AppConfig(
    server: AppConfig.ServerConfig,
    db: AppConfig.DatabaseConfig,
    keycloak: AppConfig.KeycloakConfig,
    otp: AppConfig.OtpConfig,
    credentialEncryption: AppConfig.CredentialEncryptionConfig
) derives ConfigReader

object AppConfig {
  private val technicalCredentialsKeyEnvVar = "TECHNICAL_CREDENTIALS_KEY"

  def load: AppConfig =
    loadWithEnvironment(sys.env.get)

  def loadWithEnvironment(
      environment: String => Option[String]
  ): AppConfig = {
    val loaded = ConfigSource.default.loadOrThrow[AppConfig]

    loaded.copy(
      credentialEncryption = loaded.credentialEncryption.withFallbackKey(
        environment(technicalCredentialsKeyEnvVar)
      )
    )
  }

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

  case class KeycloakConfig(
      issuer: String,
      jwksUrl: String,
      audience: String,
      authorizedParty: String,
      clockSkewSeconds: Long = 30
  )

  case class OtpConfig(
      ttlSeconds: Long,
      randomBytes: Int
  )

  case class CredentialEncryptionConfig(
      key: Option[String],
      sessionSetting: String
  ) {
    def withFallbackKey(
        fallbackKey: Option[String]
    ): CredentialEncryptionConfig =
      copy(key = key.filter(_.nonEmpty).orElse(fallbackKey.filter(_.nonEmpty)))

    def requiredKey: String =
      key
        .filter(_.nonEmpty)
        .getOrElse(
          throw IllegalStateException(
            s"Missing required $technicalCredentialsKeyEnvVar"
          )
        )

    def sessionInitSql: String =
      s"SELECT set_config('${escapeSqlLiteral(sessionSetting)}', '${escapeSqlLiteral(requiredKey)}', false)"

    private def escapeSqlLiteral(value: String): String =
      value.replace("'", "''")
  }
}
