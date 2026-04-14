package dbwatchdog.repository

import java.time.Instant
import java.util.UUID

import dbwatchdog.domain.{CreateDatabase, UpdateDatabase}
import dbwatchdog.support.PostgresIntegrationSuite

object DatabaseRepositoryIntegrationSuite extends PostgresIntegrationSuite {
  private val repo = DatabaseRepository.make

  test("insert persists a database record") { db =>
    withCleanDb(db) { db =>
      val input = sampleCreateDatabase("primary")

      for {
        persisted <- db.transact(repo.insert(input))
      } yield expect(persisted.engine == input.engine) and
        expect(persisted.host == input.host) and
        expect(persisted.port == input.port) and
        expect(persisted.technicalUser == input.technicalUser) and
        expect(persisted.technicalPassword == input.technicalPassword) and
        expect(persisted.databaseName == input.databaseName) and
        expect(persisted.deactivatedAt.isEmpty)
    }
  }

  test("update persists edited database fields") { db =>
    withCleanDb(db) { db =>
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
}
