package dbwatchdog.support

import cats.effect.std.Semaphore
import cats.effect.{IO, Resource}
import doobie.ConnectionIO
import doobie.Transactor
import doobie.implicits.*
import fly4s.data.MigrationVersion
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import weaver.Expectations
import weaver.IOSuite

import dbwatchdog.AppResources
import dbwatchdog.config.AppConfig
import dbwatchdog.database.Migration

final class ScalaPostgresContainer(image: DockerImageName)
    extends PostgreSQLContainer[ScalaPostgresContainer](image)

trait PostgresIntegrationSuite extends IOSuite {
  type Res = IntegrationDb

  override def sharedResource: Resource[IO, IntegrationDb] =
    IntegrationDb.resource

  def withCleanDb(
      db: IntegrationDb
  )(run: IntegrationDb => IO[Expectations]): IO[Expectations] =
    db.resetLock.permit.use(_ => db.reset *> run(db))
}

final case class IntegrationDb(
    xa: Transactor[IO],
    config: AppConfig,
    resetLock: Semaphore[IO]
) {
  def transact[A](query: ConnectionIO[A]): IO[A] =
    query.transact(xa)

  def reset: IO[Unit] =
    sql"TRUNCATE TABLE users, teams, databases CASCADE".update.run
      .transact(xa)
      .void

  def tableExists(tableName: String): IO[Boolean] =
    sql"""
      SELECT EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = $tableName
      )
    """.query[Boolean].unique.transact(xa)

  def columnExists(tableName: String, columnName: String): IO[Boolean] =
    sql"""
      SELECT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = $tableName
          AND column_name = $columnName
      )
    """.query[Boolean].unique.transact(xa)
}

object IntegrationDb {
  def resource: Resource[IO, IntegrationDb] =
    resource(targetVersion = None)

  def resource(
      targetVersion: Option[MigrationVersion]
  ): Resource[IO, IntegrationDb] =
    for {
      container <- postgresContainer
      resetLock <- Resource.eval(Semaphore[IO](1))
      config = AppConfig(
        server = AppConfig.ServerConfig(host = "127.0.0.1", port = 8080),
        db = AppConfig.DatabaseConfig(
          host = container.getHost,
          port = container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
          user = container.getUsername,
          password = container.getPassword,
          schema = "public",
          threadPoolSize = 2
        ),
        keycloak = AppConfig.KeycloakConfig(
          issuer = "https://issuer.example.test/realms/db-watchdog",
          jwksUrl = "https://issuer.example.test/jwks",
          audience = "db-watchdog-backend",
          authorizedParty = "db-watchdog-frontend",
          clockSkewSeconds = 30
        ),
        otp = AppConfig.OtpConfig(
          ttlSeconds = 300,
          randomBytes = 18
        ),
        credentialEncryption = AppConfig.CredentialEncryptionConfig(
          key = Some("integration-technical-credentials-key"),
          previousKey = None
        )
      )
      _ <- Resource.eval {
        given AppConfig = config
        Migration.migrate(targetVersion).void
      }
      xa <- AppResources.makePostgresTransactor(config)
    } yield IntegrationDb(xa = xa, config = config, resetLock = resetLock)

  private def postgresContainer: Resource[IO, ScalaPostgresContainer] =
    Resource.make {
      IO.blocking {
        val container =
          new ScalaPostgresContainer(
            DockerImageName.parse("postgres:16-alpine")
          )
            .withDatabaseName("public")
            .withUsername("test")
            .withPassword("test")
        container.start()
        container
      }
    } { container =>
      IO.blocking(container.stop()).handleError(_ => ())
    }
}
