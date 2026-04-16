package dbwatchdog.repository

import java.time.Instant
import java.util.UUID

import doobie.ConnectionIO
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.config.AppConfig
import dbwatchdog.domain.{CreateDatabase, UpdateDatabase}
import dbwatchdog.support.PostgresIntegrationSuite

object DatabaseRepositoryIntegrationSuite extends PostgresIntegrationSuite {
  private val currentKey = "integration-technical-credentials-key"
  private val previousKey = "previous-technical-credentials-key"

  test("insert persists a database record") { db =>
    withCleanDb(db) { db =>
      given AppConfig = db.config
      val repo = DatabaseRepository.make
      val input = sampleCreateDatabase("primary")

      for {
        persisted <- db.transact(repo.insert(input))
        persistedId = persisted.id
        plaintextColumnExists <- db.columnExists(
          "databases",
          "technical_password"
        )
        ciphertext <- db.transact(
          sql"""
            SELECT technical_password_ciphertext
            FROM databases
            WHERE id = $persistedId
          """.query[Array[Byte]].unique
        )
      } yield expect(persisted.engine == input.engine) and
        expect(persisted.host == input.host) and
        expect(persisted.port == input.port) and
        expect(persisted.technicalUser == input.technicalUser) and
        expect(persisted.technicalPassword == input.technicalPassword) and
        expect(persisted.databaseName == input.databaseName) and
        expect(persisted.deactivatedAt.isEmpty) and
        expect(!plaintextColumnExists) and
        expect(ciphertext.nonEmpty)
    }
  }

  test("update persists edited database fields") { db =>
    withCleanDb(db) { db =>
      given AppConfig = db.config
      val repo = DatabaseRepository.make
      val input = sampleCreateDatabase("editable")

      for {
        persisted <- db.transact(repo.insert(input))
        updated <- db.transact(
          repo.update(
            persisted.id,
            UpdateDatabase(
              engine = "postgres",
              host = "edited.internal",
              port = 6432,
              technicalUser = "edited_user",
              technicalPassword = "rotated-secret",
              databaseName = "analytics_reporting"
            )
          )
        )
        loaded <- db.transact(repo.findById(persisted.id))
      } yield expect(updated.id == persisted.id) and
        expect(updated.host == "edited.internal") and
        expect(updated.port == 6432) and
        expect(updated.technicalUser == "edited_user") and
        expect(updated.technicalPassword == "rotated-secret") and
        expect(updated.databaseName == "analytics_reporting") and
        expect(loaded.contains(updated))
    }
  }

  test(
    "deactivate and reactivate drive active-only lookups and timestamp transitions"
  ) { db =>
    withCleanDb(db) { db =>
      given AppConfig = db.config
      val repo = DatabaseRepository.make
      val deactivateAt = Instant.parse("2026-04-14T10:00:00Z")
      val laterDeactivateAt = Instant.parse("2026-04-14T11:00:00Z")

      for {
        active <- db.transact(repo.insert(sampleCreateDatabase("active")))
        other <- db.transact(repo.insert(sampleCreateDatabase("other")))
        initiallyActive <- db.transact(repo.findActiveById(active.id))
        initiallySelected <- db.transact(
          repo.findActiveByIds(Set(active.id, other.id))
        )
        deactivated <- db.transact(repo.deactivate(active.id, deactivateAt))
        deactivatedAgain <- db.transact(
          repo.deactivate(active.id, laterDeactivateAt)
        )
        activeAfterDeactivate <- db.transact(repo.findActiveById(active.id))
        selectedAfterDeactivate <- db.transact(
          repo.findActiveByIds(Set(active.id, other.id))
        )
        reactivated <- db.transact(repo.reactivate(active.id))
        reactivatedAgain <- db.transact(repo.reactivate(active.id))
        activeAfterReactivate <- db.transact(repo.findActiveById(active.id))
        selectedAfterReactivate <- db.transact(
          repo.findActiveByIds(Set(active.id, other.id))
        )
      } yield expect(initiallyActive.exists(_.id == active.id)) and
        expect(
          initiallySelected.map(_.id).toSet == Set(active.id, other.id)
        ) and
        expect(deactivated.deactivatedAt.nonEmpty) and
        expect(deactivatedAgain.deactivatedAt == deactivated.deactivatedAt) and
        expect(activeAfterDeactivate.isEmpty) and
        expect(selectedAfterDeactivate.map(_.id) == List(other.id)) and
        expect(reactivated.deactivatedAt.isEmpty) and
        expect(reactivatedAgain.deactivatedAt.isEmpty) and
        expect(activeAfterReactivate.exists(_.id == active.id)) and
        expect(
          selectedAfterReactivate.map(_.id).toSet == Set(active.id, other.id)
        )
    }
  }

  test("mixed-key rows stay readable through repository list and findById") {
    db =>
      withCleanDb(db) { db =>
        given AppConfig = db.config
        val repo = DatabaseRepository.make

        for {
          currentId <- db.transact(
            insertEncryptedDatabase(
              databaseName = "current_mixed_db",
              technicalUser = "current_user",
              technicalPassword = "current-secret",
              encryptionKey = currentKey
            )
          )
          previousId <- db.transact(
            insertEncryptedDatabase(
              databaseName = "previous_mixed_db",
              technicalUser = "previous_user",
              technicalPassword = "previous-secret",
              encryptionKey = previousKey
            )
          )
          currentDatabase <- db.transact(
            withCredentialSettings(db, currentKey, Some(previousKey)) {
              repo.findById(currentId)
            }
          )
          listedDatabases <- db.transact(
            withCredentialSettings(db, currentKey, Some(previousKey)) {
              repo.list
            }
          )
        } yield expect(
          currentDatabase.exists(_.technicalPassword == "current-secret")
        ) and expect(
          listedDatabases
            .find(_.id == previousId)
            .exists(_.technicalPassword == "previous-secret")
        )
      }
  }

  test("rewrapTechnicalCredentials updates only previous-key rows") { db =>
    withCleanDb(db) { db =>
      given AppConfig = db.config
      val repo = DatabaseRepository.make

      for {
        currentId <- db.transact(
          insertEncryptedDatabase(
            databaseName = "current_rewrap_db",
            technicalUser = "current_user",
            technicalPassword = "current-secret",
            encryptionKey = currentKey
          )
        )
        previousId <- db.transact(
          insertEncryptedDatabase(
            databaseName = "previous_rewrap_db",
            technicalUser = "previous_user",
            technicalPassword = "previous-secret",
            encryptionKey = previousKey
          )
        )
        currentUpdatedAtBefore <- db.transact(databaseUpdatedAt(currentId))
        previousUpdatedAtBefore <- db.transact(databaseUpdatedAt(previousId))
        updatedCount <- db.transact(
          withCredentialSettings(db, currentKey, Some(previousKey)) {
            repo.rewrapTechnicalCredentials()
          }
        )
        currentUpdatedAtAfter <- db.transact(databaseUpdatedAt(currentId))
        previousUpdatedAtAfter <- db.transact(databaseUpdatedAt(previousId))
        currentNeedsRewrap <- db.transact(
          withCredentialSettings(db, currentKey, Some(previousKey)) {
            technicalPasswordNeedsRewrap(currentId)
          }
        )
        previousNeedsRewrap <- db.transact(
          withCredentialSettings(db, currentKey, Some(previousKey)) {
            technicalPasswordNeedsRewrap(previousId)
          }
        )
        rewrappedPrevious <- db.transact(
          withCredentialSettings(db, currentKey, None) {
            repo.findById(previousId)
          }
        )
      } yield expect(updatedCount == 1) and
        expect(currentUpdatedAtAfter == currentUpdatedAtBefore) and
        expect(previousUpdatedAtAfter.isAfter(previousUpdatedAtBefore)) and
        expect(!currentNeedsRewrap) and
        expect(!previousNeedsRewrap) and
        expect(
          rewrappedPrevious.exists(_.technicalPassword == "previous-secret")
        )
    }
  }

  test("rewrapTechnicalCredentials is idempotent at the row-count level") {
    db =>
      withCleanDb(db) { db =>
        given AppConfig = db.config
        val repo = DatabaseRepository.make

        for {
          _ <- db.transact(
            insertEncryptedDatabase(
              databaseName = "idempotent_rewrap_db",
              technicalUser = "previous_user",
              technicalPassword = "previous-secret",
              encryptionKey = previousKey
            )
          )
          firstRunCount <- db.transact(
            withCredentialSettings(db, currentKey, Some(previousKey)) {
              repo.rewrapTechnicalCredentials()
            }
          )
          secondRunCount <- db.transact(
            withCredentialSettings(db, currentKey, Some(previousKey)) {
              repo.rewrapTechnicalCredentials()
            }
          )
        } yield expect(firstRunCount == 1) and expect(secondRunCount == 0)
      }
  }

  private def sampleCreateDatabase(label: String): CreateDatabase = {
    val suffix = s"${label}_${UUID.randomUUID().toString.take(8)}"

    CreateDatabase(
      engine = "postgres",
      host = s"db-$suffix.local",
      port = 5432,
      technicalUser = s"db_user_$suffix",
      technicalPassword = "secret",
      databaseName = suffix
    )
  }

  private def withCredentialSettings[A](
      db: dbwatchdog.support.IntegrationDb,
      currentKey: String,
      previousKey: Option[String]
  )(run: ConnectionIO[A]): ConnectionIO[A] =
    for {
      _ <- sql"""
        SELECT set_config(${AppConfig.technicalCredentialsSessionSetting}, $currentKey, false),
               set_config(
                 ${AppConfig.technicalCredentialsPreviousSessionSetting},
                 ${previousKey.getOrElse("")},
                 false
               )
      """.query[(String, String)].unique.map(_ => ())
      result <- run
    } yield result

  private def insertEncryptedDatabase(
      databaseName: String,
      technicalUser: String,
      technicalPassword: String,
      encryptionKey: String
  ): ConnectionIO[UUID] =
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
        $technicalUser,
        pgp_sym_encrypt($technicalPassword, $encryptionKey, 'cipher-algo=aes256'),
        $databaseName
      )
      RETURNING id
    """.query[UUID].unique

  private def technicalPasswordNeedsRewrap(
      databaseId: UUID
  ): ConnectionIO[Boolean] =
    sql"""
      SELECT technical_password_needs_rewrap(technical_password_ciphertext)
      FROM databases
      WHERE id = $databaseId
    """.query[Boolean].unique

  private def databaseUpdatedAt(databaseId: UUID): ConnectionIO[Instant] =
    sql"""
      SELECT updated_at
      FROM databases
      WHERE id = $databaseId
    """.query[Instant].unique
}
