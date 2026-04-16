package dbwatchdog.config

import cats.effect.IO
import pureconfig.ConfigSource
import weaver.SimpleIOSuite

object AppConfigSuite extends SimpleIOSuite {
  private val secureKeycloak = AppConfig.KeycloakConfig(
    issuer = "https://issuer.example.test/realms/db-watchdog",
    jwksUrl = "https://issuer.example.test/jwks",
    audience = "db-watchdog-backend",
    authorizedParty = "db-watchdog-frontend"
  )

  private val localHttpKeycloak = secureKeycloak.copy(
    issuer = "http://localhost:8180/realms/db-watchdog",
    jwksUrl = "http://127.0.0.1:8180/jwks"
  )

  private val localIpv6HttpKeycloak = secureKeycloak.copy(
    issuer = "http://[::1]:8180/realms/db-watchdog",
    jwksUrl = "http://[::1]:8180/jwks"
  )

  private val remoteHttpKeycloak = secureKeycloak.copy(
    issuer = "http://keycloak.internal.example.test/realms/db-watchdog",
    jwksUrl = "http://example.test/jwks"
  )

  test("loads a complete config from HOCON") {
    val loaded = ConfigSource
      .string("""
        |server {
        |  host = "127.0.0.1"
        |  port = 8081
        |}
        |db {
        |  host = "localhost"
        |  port = 5432
        |  user = "postgres"
        |  password = "password"
        |  schema = "public"
        |  thread-pool-size = 5
        |}
        |keycloak {
        |  issuer = "https://issuer.example.test/realms/db-watchdog"
        |  jwks-url = "https://issuer.example.test/jwks"
        |  audience = "db-watchdog-backend"
        |  authorized-party = "db-watchdog-frontend"
        |  clock-skew-seconds = 30
        |}
        |otp {
        |  ttl-seconds = 300
        |  random-bytes = 18
        |}
        |credential-encryption {
        |  key = "test-key"
        |  previous-key = "old-key"
        |  session-setting = "app.technical_credentials_key"
        |  previous-session-setting = "app.previous_technical_credentials_key"
        |}
        |""".stripMargin)
      .load[AppConfig]

    IO.pure(
      expect(loaded.exists(_.server.host == "127.0.0.1")) and
        expect(loaded.exists(_.server.port == 8081)) and
        expect(loaded.exists(_.db.schema == "public")) and
        expect(loaded.exists(_.keycloak.audience == "db-watchdog-backend")) and
        expect(loaded.exists(_.otp.ttlSeconds == 300L)) and
        expect(
          loaded.exists(_.keycloak.authorizedParty == "db-watchdog-frontend")
        ) and
        expect(
          loaded.exists(
            _.credentialEncryption.sessionSetting ==
              "app.technical_credentials_key"
          )
        ) and
        expect(
          loaded.exists(
            _.credentialEncryption.previousKey.contains("old-key")
          )
        ) and
        expect(
          loaded.exists(
            _.credentialEncryption.previousSessionSetting ==
              "app.previous_technical_credentials_key"
          )
        )
    )
  }

  test("fails when a required field is missing") {
    val loaded = ConfigSource
      .string("""
        |server {
        |  host = "127.0.0.1"
        |}
        |db {
        |  host = "localhost"
        |  port = 5432
        |  user = "postgres"
        |  password = "password"
        |  schema = "public"
        |  thread-pool-size = 5
        |}
        |keycloak {
        |  issuer = "https://issuer.example.test/realms/db-watchdog"
        |  jwks-url = "https://issuer.example.test/jwks"
        |  audience = "db-watchdog-backend"
        |  authorized-party = "db-watchdog-frontend"
        |}
        |otp {
        |  ttl-seconds = 300
        |  random-bytes = 18
        |}
        |credential-encryption {
        |  key = "test-key"
        |  previous-key = "old-key"
        |  session-setting = "app.technical_credentials_key"
        |  previous-session-setting = "app.previous_technical_credentials_key"
        |}
        |""".stripMargin)
      .load[AppConfig]

    IO.pure(expect(loaded.isLeft))
  }

  test("AppConfig.load reads the default application config") {
    val loaded = AppConfig.loadWithEnvironment {
      case "TECHNICAL_CREDENTIALS_KEY" => Some("test-key")
      case _                           => None
    }

    IO.pure(
      expect(loaded.server.host == "localhost") and
        expect(loaded.server.port == 8080) and
        expect(loaded.server.hostIp4s.toString == "localhost") and
        expect(loaded.server.portIp4s.value == 8080) and
        expect(
          loaded.db.url == "jdbc:postgresql://localhost:54320/db_watchdog"
        ) and
        expect(loaded.otp.randomBytes == 18) and
        expect(loaded.credentialEncryption.requiredKey == "test-key") and
        expect(loaded.credentialEncryption.normalizedPreviousKey.isEmpty)
    )
  }

  test("AppConfig.loadWithEnvironment loads the optional previous key") {
    val loaded = AppConfig.loadWithEnvironment {
      case "TECHNICAL_CREDENTIALS_KEY"          => Some("test-key")
      case "TECHNICAL_CREDENTIALS_PREVIOUS_KEY" => Some("old-key")
      case _                                    => None
    }

    IO.pure(
      expect(
        loaded.credentialEncryption.normalizedPreviousKey.contains("old-key")
      )
    )
  }

  test("blank TECHNICAL_CREDENTIALS_PREVIOUS_KEY is treated as absent") {
    val loaded = AppConfig.loadWithEnvironment {
      case "TECHNICAL_CREDENTIALS_KEY"          => Some("test-key")
      case "TECHNICAL_CREDENTIALS_PREVIOUS_KEY" => Some("   ")
      case _                                    => None
    }

    IO.pure(expect(loaded.credentialEncryption.normalizedPreviousKey.isEmpty))
  }

  test("session init SQL configures current and previous credential settings") {
    val config = AppConfig.CredentialEncryptionConfig(
      key = Some("current-key"),
      previousKey = Some("old-key"),
      sessionSetting = "app.technical_credentials_key",
      previousSessionSetting = "app.previous_technical_credentials_key"
    )

    IO.pure(
      expect(
        config.sessionInitSql.contains(
          "set_config('app.technical_credentials_key', 'current-key', false)"
        )
      ) and
        expect(
          config.sessionInitSql.contains(
            "set_config('app.previous_technical_credentials_key', 'old-key', false)"
          )
        )
    )
  }

  test(
    "AppConfig.loadWithEnvironment fails when TECHNICAL_CREDENTIALS_KEY is missing"
  ) {
    IO {
      val threw =
        try {
          AppConfig
            .loadWithEnvironment(_ => None)
            .credentialEncryption
            .requiredKey
          false
        } catch {
          case _: IllegalStateException => true
        }

      expect(threw)
    }
  }

  test(
    "AppConfig.loadWithEnvironment fails when TECHNICAL_CREDENTIALS_KEY is blank"
  ) {
    IO {
      val threw =
        try {
          AppConfig
            .loadWithEnvironment {
              case "TECHNICAL_CREDENTIALS_KEY" => Some("   ")
              case _                           => None
            }
            .credentialEncryption
            .requiredKey
          false
        } catch {
          case _: IllegalStateException => true
        }

      expect(threw)
    }
  }

  test("https Keycloak endpoints do not generate transport warnings") {
    IO.pure(expect(secureKeycloak.transportSecurityWarnings.isEmpty))
  }

  test("local http Keycloak endpoints generate local-dev-only warnings") {
    val warnings = localHttpKeycloak.transportSecurityWarnings

    IO.pure(
      expect(warnings.length == 2) and
        expect(
          warnings.forall(_.contains("acceptable only for local development"))
        ) and
        expect(warnings.exists(_.contains("keycloak.issuer"))) and
        expect(warnings.exists(_.contains("keycloak.jwks-url")))
    )
  }

  test("local IPv6 http Keycloak endpoints generate local-dev-only warnings") {
    val warnings = localIpv6HttpKeycloak.transportSecurityWarnings

    IO.pure(
      expect(warnings.length == 2) and
        expect(
          warnings.forall(_.contains("acceptable only for local development"))
        ) and
        expect(warnings.exists(_.contains("keycloak.issuer"))) and
        expect(warnings.exists(_.contains("keycloak.jwks-url")))
    )
  }

  test("non-local http Keycloak endpoints generate deployment warnings") {
    val warnings = remoteHttpKeycloak.transportSecurityWarnings

    IO.pure(
      expect(warnings.length == 2) and
        expect(
          warnings.forall(
            _.contains("not acceptable outside local development")
          )
        ) and
        expect(warnings.exists(_.contains("keycloak.issuer"))) and
        expect(warnings.exists(_.contains("keycloak.jwks-url")))
    )
  }
}
