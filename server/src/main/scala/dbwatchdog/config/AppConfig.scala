package dbwatchdog.config

import java.net.URI
import java.util.Locale

import com.comcast.ip4s.{Host, Port}
import pureconfig.{ConfigReader, ConfigSource}

case class AppConfig(
    server: AppConfig.ServerConfig,
    db: AppConfig.DatabaseConfig,
    keycloak: AppConfig.KeycloakConfig,
    otp: AppConfig.OtpConfig,
    credentialEncryption: AppConfig.CredentialEncryptionConfig
) derives ConfigReader {
  def transportSecurityWarnings: List[String] =
    keycloak.transportSecurityWarnings
}

object AppConfig {
  private val technicalCredentialsKeyEnvVar = "TECHNICAL_CREDENTIALS_KEY"
  private val technicalCredentialsPreviousKeyEnvVar =
    "TECHNICAL_CREDENTIALS_PREVIOUS_KEY"
  private val localHosts = Set("localhost", "127.0.0.1", "::1")
  val technicalCredentialsSessionSetting = "app.technical_credentials_key"
  val technicalCredentialsPreviousSessionSetting =
    "app.previous_technical_credentials_key"

  def load: AppConfig =
    loadWithEnvironment(sys.env.get)

  def loadWithEnvironment(
      environment: String => Option[String]
  ): AppConfig = {
    val loaded = ConfigSource.default.loadOrThrow[AppConfig]

    loaded.copy(
      credentialEncryption = loaded.credentialEncryption.withFallbackKeys(
        key = environment(technicalCredentialsKeyEnvVar),
        previousKey = environment(technicalCredentialsPreviousKeyEnvVar)
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
  ) {
    def transportSecurityWarnings: List[String] =
      List(
        transportSecurityWarning("keycloak.issuer", issuer),
        transportSecurityWarning("keycloak.jwks-url", jwksUrl)
      ).flatten
  }

  case class OtpConfig(
      ttlSeconds: Long,
      randomBytes: Int
  )

  case class CredentialEncryptionConfig(
      key: Option[String],
      previousKey: Option[String]
  ) {
    private def nonBlank(value: String): Boolean = value.trim.nonEmpty

    def withFallbackKeys(
        key: Option[String],
        previousKey: Option[String]
    ): CredentialEncryptionConfig =
      copy(
        key = this.key.filter(nonBlank).orElse(key.filter(nonBlank)),
        previousKey = this.previousKey
          .filter(nonBlank)
          .orElse(previousKey.filter(nonBlank))
      )

    def requiredKey: String =
      key
        .filter(nonBlank)
        .getOrElse(
          throw IllegalStateException(
            s"Missing required $technicalCredentialsKeyEnvVar"
          )
        )

    def normalizedPreviousKey: Option[String] =
      previousKey.filter(nonBlank)

    def sessionInitSql: String =
      s"SELECT ${sessionSettingSql(technicalCredentialsSessionSetting, requiredKey)}, ${sessionSettingSql(technicalCredentialsPreviousSessionSetting, normalizedPreviousKey.getOrElse(""))}"

    private def sessionSettingSql(
        settingName: String,
        value: String
    ): String =
      s"set_config('${escapeSqlLiteral(settingName)}', '${escapeSqlLiteral(value)}', false)"

    private def escapeSqlLiteral(value: String): String =
      value.replace("'", "''")
  }

  private def transportSecurityWarning(
      settingName: String,
      uriValue: String
  ): Option[String] =
    uriScheme(uriValue) match {
      case Some("https")                         => None
      case Some("http") if isLocalHost(uriValue) =>
        Some(
          s"Transport security warning: $settingName uses HTTP for a local Keycloak endpoint. This is acceptable only for local development; use HTTPS outside local development."
        )
      case Some("http") =>
        Some(
          s"Transport security warning: $settingName uses HTTP for a non-local Keycloak endpoint. This is not acceptable outside local development; configure HTTPS."
        )
      case _ => None
    }

  private def uriScheme(uriValue: String): Option[String] =
    parseUri(uriValue)
      .flatMap(uri => Option(uri.getScheme))
      .map(_.toLowerCase(Locale.ROOT))

  private def isLocalHost(uriValue: String): Boolean =
    parseUri(uriValue)
      .flatMap(uri => Option(uri.getHost))
      .map(normalizeHost)
      .exists(localHosts.contains)

  private def normalizeHost(host: String): String =
    host
      .stripPrefix("[")
      .stripSuffix("]")
      .toLowerCase(Locale.ROOT)

  private def parseUri(uriValue: String): Option[URI] =
    try {
      Some(URI.create(uriValue))
    } catch {
      case _: IllegalArgumentException => None
    }
}
