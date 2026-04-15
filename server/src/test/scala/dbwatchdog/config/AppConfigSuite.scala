package dbwatchdog.config

import cats.effect.IO
import pureconfig.ConfigSource
import weaver.SimpleIOSuite

object AppConfigSuite extends SimpleIOSuite {

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
        |  session-setting = "app.technical_credentials_key"
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
        |  session-setting = "app.technical_credentials_key"
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
        expect(loaded.credentialEncryption.requiredKey == "test-key")
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
}
