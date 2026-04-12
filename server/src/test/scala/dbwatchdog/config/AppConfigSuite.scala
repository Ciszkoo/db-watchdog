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
        |  clock-skew-seconds = 30
        |}
        |""".stripMargin)
      .load[AppConfig]

    IO.pure(
      expect(loaded.exists(_.server.host == "127.0.0.1")) and
        expect(loaded.exists(_.server.port == 8081)) and
        expect(loaded.exists(_.db.schema == "public")) and
        expect(loaded.exists(_.keycloak.audience == "db-watchdog-backend"))
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
        |}
        |""".stripMargin)
      .load[AppConfig]

    IO.pure(expect(loaded.isLeft))
  }

  test("AppConfig.load reads the default application config") {
    val loaded = AppConfig.load

    IO.pure(
      expect(loaded.server.host == "localhost") and
        expect(loaded.server.port == 8080) and
        expect(loaded.server.hostIp4s.toString == "localhost") and
        expect(loaded.server.portIp4s.value == 8080) and
        expect(loaded.db.url == "jdbc:postgresql://localhost:5432/db_watchdog")
    )
  }
}
