package dbwatchdog.database

import cats.effect.IO
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.support.{IntegrationDb, PostgresIntegrationSuite}
import weaver.Expectations

object MigrationIntegrationSuite extends PostgresIntegrationSuite {
  private val currentKey = "integration-technical-credentials-key"
  private val previousKey = "previous-technical-credentials-key"

  test("migrations create the required tables") { db =>
    assertRequiredTables(db)
  }

  test("latest schema keeps only encrypted technical credential storage") {
    db =>
      for {
        plaintextColumn <- db.columnExists("databases", "technical_password")
        ciphertextColumn <- db.columnExists(
          "databases",
          "technical_password_ciphertext"
        )
      } yield expect(!plaintextColumn) and expect(ciphertextColumn)
  }

  test("rotation helpers decrypt rows already encrypted with the current key") {
    db =>
      withCleanDb(db) { db =>
        for {
          databaseId <- db.transact(
            insertEncryptedDatabase(
              databaseName = "current_helper_db",
              technicalPassword = "current-secret",
              encryptionKey = currentKey
            )
          )
          result <- db.transact(
            withCredentialSettings(db, currentKey, Some(previousKey)) {
              sql"""
                SELECT decrypt_technical_password(technical_password_ciphertext),
                       technical_password_needs_rewrap(technical_password_ciphertext)
                FROM databases
                WHERE id = $databaseId
              """.query[(String, Boolean)].unique
            }
          )
        } yield expect(result == ("current-secret", false))
      }
  }

  test(
    "rotation helper overload decrypts correctly inside a single statement"
  ) { db =>
    withCleanDb(db) { db =>
      for {
        databaseId <- db.transact(
          insertEncryptedDatabase(
            databaseName = "single_statement_helper_db",
            technicalPassword = "single-secret",
            encryptionKey = currentKey
          )
        )
        decrypted <- db.transact(
          sql"""
              WITH configured_session AS (
                SELECT set_config('app.technical_credentials_key', $currentKey, false),
                       set_config('app.previous_technical_credentials_key', '', false)
              )
              SELECT decrypt_technical_password(
                technical_password_ciphertext,
                $currentKey,
                ''
              )
              FROM databases, configured_session
              WHERE id = $databaseId
            """.query[String].unique
        )
      } yield expect(decrypted == "single-secret")
    }
  }

  test(
    "rotation helpers detect previous-key rows and rewrap them with the current key"
  ) { db =>
    withCleanDb(db) { db =>
      for {
        databaseId <- db.transact(
          insertEncryptedDatabase(
            databaseName = "previous_helper_db",
            technicalPassword = "previous-secret",
            encryptionKey = previousKey
          )
        )
        beforeRewrap <- db.transact(
          withCredentialSettings(db, currentKey, Some(previousKey)) {
            sql"""
                SELECT technical_password_needs_rewrap(technical_password_ciphertext)
                FROM databases
                WHERE id = $databaseId
              """.query[Boolean].unique
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
      } yield expect(beforeRewrap) and
        expect(afterRewrap == ("previous-secret", false))
    }
  }

  private def assertRequiredTables(db: IntegrationDb): IO[Expectations] =
    for {
      users <- db.tableExists("users")
      teams <- db.tableExists("teams")
      databases <- db.tableExists("databases")
      teamDatabaseGrants <- db.tableExists("team_database_grants")
      userDatabaseAccessExtensions <- db.tableExists(
        "user_database_access_extensions"
      )
      temporaryAccessCredentials <- db.tableExists(
        "temporary_access_credentials"
      )
      databaseSessions <- db.tableExists("database_sessions")
    } yield expect(users) and
      expect(teams) and
      expect(databases) and
      expect(teamDatabaseGrants) and
      expect(userDatabaseAccessExtensions) and
      expect(temporaryAccessCredentials) and
      expect(databaseSessions)

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

  private def insertEncryptedDatabase(
      databaseName: String,
      technicalPassword: String,
      encryptionKey: String
  ): ConnectionIO[java.util.UUID] =
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
        'db.internal',
        5432,
        'technical_user',
        pgp_sym_encrypt($technicalPassword, $encryptionKey, 'cipher-algo=aes256'),
        $databaseName
      )
      RETURNING id
    """.query[java.util.UUID].unique
}
