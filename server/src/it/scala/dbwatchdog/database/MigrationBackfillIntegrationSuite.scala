package dbwatchdog.database

import java.util.UUID

import doobie.implicits.*
import doobie.postgres.implicits.*
import fly4s.data.MigrationVersion

import dbwatchdog.config.AppConfig
import dbwatchdog.repository.DatabaseRepository
import dbwatchdog.support.{IntegrationDb, PostgresIntegrationSuite}

object MigrationBackfillIntegrationSuite extends PostgresIntegrationSuite {
  override def sharedResource =
    IntegrationDb.resource(targetVersion = Some(MigrationVersion("0006")))

  test(
    "V0007 backfills plaintext technical credentials and drops the plaintext column"
  ) { db =>
    db.resetLock.permit.use { _ =>
      for {
        _ <- db.reset
        insertedId <- db.transact(
          sql"""
              INSERT INTO databases (
                engine,
                host,
                port,
                technical_user,
                technical_password,
                database_name
              )
              VALUES (
                'postgres',
                'legacy.internal',
                5432,
                'legacy_user',
                'legacy-secret',
                'legacy_db'
              )
              RETURNING id
            """.query[UUID].unique
        )
        plaintextBefore <- db.columnExists("databases", "technical_password")
        _ <- {
          given AppConfig = db.config
          Migration.migrate()
        }
        plaintextAfter <- db.columnExists("databases", "technical_password")
        ciphertextAfter <- db.columnExists(
          "databases",
          "technical_password_ciphertext"
        )
        decrypted <- {
          given AppConfig = db.config
          db.transact(DatabaseRepository.make.findById(insertedId))
        }
        rawCiphertext <- db.transact(
          sql"""
              SELECT technical_password_ciphertext
              FROM databases
              WHERE id = $insertedId
            """.query[Array[Byte]].unique
        )
      } yield expect(plaintextBefore) and
        expect(!plaintextAfter) and
        expect(ciphertextAfter) and
        expect(decrypted.exists(_.technicalPassword == "legacy-secret")) and
        expect(rawCiphertext.nonEmpty)
    }
  }
}
