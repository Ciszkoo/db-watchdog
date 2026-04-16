package dbwatchdog.database

import java.sql.DriverManager
import java.util.UUID

import cats.effect.std.Semaphore
import cats.effect.{IO, Resource}
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.postgres.implicits.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import weaver.IOSuite

import dbwatchdog.AppResources
import dbwatchdog.config.AppConfig
import dbwatchdog.domain.{CreateDatabase, UpdateDatabase}
import dbwatchdog.repository.DatabaseRepository
import dbwatchdog.support.{IntegrationDb, ScalaPostgresContainer}

object MigrationPgcryptoSchemaCompatibilitySuite extends IOSuite {
  type Res = IntegrationDb

  private val currentKey = "integration-technical-credentials-key"
  private val previousKey = "previous-technical-credentials-key"

  override def sharedResource: Resource[IO, IntegrationDb] =
    for {
      container <- postgresContainer
      resetLock <- Resource.eval(Semaphore[IO](1))
      config = appConfig(container)
      _ <- Resource.eval(bootstrapPgcryptoInPublic(config))
      _ <- Resource.eval {
        given AppConfig = config
        Migration.migrate().void
      }
      xa <- AppResources.makePostgresTransactor(config)
    } yield IntegrationDb(xa = xa, config = config, resetLock = resetLock)

  test(
    "rotation helpers work when pgcrypto lives in public and app objects live in db_watchdog"
  ) { db =>
    db.resetLock.permit.use { _ =>
      for {
        _ <- db.reset
        databaseId <- db.transact(
          sql"""
            INSERT INTO databases (
              engine,
              host,
              port,
              technical_user,
              technical_password_ciphertext,
              database_name
            )
            VALUES (
              'postgres',
              'legacy.internal',
              5432,
              'legacy_user',
              public.pgp_sym_encrypt(
                'legacy-secret',
                $previousKey,
                'cipher-algo=aes256'
              ),
              'legacy_db'
            )
            RETURNING id
          """.query[UUID].unique
        )
        beforeRewrap <- db.transact(
          withCredentialSettings(db, currentKey, Some(previousKey)) {
            sql"""
              SELECT decrypt_technical_password(technical_password_ciphertext),
                     technical_password_needs_rewrap(technical_password_ciphertext)
              FROM databases
              WHERE id = $databaseId
            """.query[(String, Boolean)].unique
          }
        )
        _ <- db.transact(
          withCredentialSettings(db, currentKey, Some(previousKey)) {
            sql"""
              UPDATE databases
              SET technical_password_ciphertext =
                rewrap_technical_password(technical_password_ciphertext)
              WHERE id = $databaseId
            """.update.run
          }
        )
        afterRewrap <- db.transact(
          withCredentialSettings(db, currentKey, None) {
            sql"""
              SELECT decrypt_technical_password(technical_password_ciphertext),
                     technical_password_needs_rewrap(technical_password_ciphertext)
              FROM databases
              WHERE id = $databaseId
            """.query[(String, Boolean)].unique
          }
        )
      } yield expect(beforeRewrap == ("legacy-secret", true)) and
        expect(afterRewrap == ("legacy-secret", false))
    }
  }

  test(
    "database repository writes stay compatible when pgcrypto lives in public"
  ) { db =>
    db.resetLock.permit.use { _ =>
      given AppConfig = db.config
      val repo = DatabaseRepository.make

      for {
        _ <- db.reset
        inserted <- db.transact(
          repo.insert(
            CreateDatabase(
              engine = "postgres",
              host = "compatible.internal",
              port = 5432,
              technicalUser = "compatible_user",
              technicalPassword = "initial-secret",
              databaseName = "compatible_db"
            )
          )
        )
        updated <- db.transact(
          repo.update(
            inserted.id,
            UpdateDatabase(
              engine = "postgres",
              host = "compatible-updated.internal",
              port = 6432,
              technicalUser = "updated_user",
              technicalPassword = "rotated-secret",
              databaseName = "compatible_reporting"
            )
          )
        )
        loaded <- db.transact(repo.findById(inserted.id))
        ciphertext <- db.transact(
          sql"""
            SELECT technical_password_ciphertext
            FROM databases
            WHERE id = ${inserted.id}
          """.query[Array[Byte]].unique
        )
      } yield expect(inserted.technicalPassword == "initial-secret") and
        expect(updated.host == "compatible-updated.internal") and
        expect(updated.port == 6432) and
        expect(updated.technicalUser == "updated_user") and
        expect(updated.technicalPassword == "rotated-secret") and
        expect(updated.databaseName == "compatible_reporting") and
        expect(loaded.contains(updated)) and
        expect(ciphertext.nonEmpty)
    }
  }

  private def appConfig(container: ScalaPostgresContainer): AppConfig =
    AppConfig(
      server = AppConfig.ServerConfig(host = "127.0.0.1", port = 8080),
      db = AppConfig.DatabaseConfig(
        host = container.getHost,
        port = container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
        user = container.getUsername,
        password = container.getPassword,
        schema = "db_watchdog",
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
        key = Some(currentKey),
        previousKey = None,
        sessionSetting = "app.technical_credentials_key",
        previousSessionSetting = "app.previous_technical_credentials_key"
      )
    )

  private def bootstrapPgcryptoInPublic(config: AppConfig): IO[Unit] =
    IO.blocking {
      DriverManager.registerDriver(org.postgresql.Driver())

      val connection =
        DriverManager.getConnection(
          config.db.url,
          config.db.user,
          config.db.password
        )

      try {
        val statement = connection.createStatement()

        try {
          statement.execute("CREATE SCHEMA IF NOT EXISTS db_watchdog")
          statement.execute(
            "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public"
          )
        } finally statement.close()
      } finally connection.close()
    }

  private def withCredentialSettings[A](
      db: IntegrationDb,
      currentKey: String,
      previousKey: Option[String]
  )(run: ConnectionIO[A]): ConnectionIO[A] =
    for {
      _ <- sql"""
        SELECT set_config(${db.config.credentialEncryption.sessionSetting}, $currentKey, false),
               set_config(
                 ${db.config.credentialEncryption.previousSessionSetting},
                 ${previousKey.getOrElse("")},
                 false
               )
      """.query[(String, String)].unique.map(_ => ())
      result <- run
    } yield result

  private def postgresContainer: Resource[IO, ScalaPostgresContainer] =
    Resource.make {
      IO.blocking {
        val container =
          new ScalaPostgresContainer(
            DockerImageName.parse("postgres:16-alpine")
          )
            .withDatabaseName("db_watchdog")
            .withUsername("test")
            .withPassword("test")
        container.start()
        container
      }
    } { container =>
      IO.blocking(container.stop()).handleError(_ => ())
    }
}
