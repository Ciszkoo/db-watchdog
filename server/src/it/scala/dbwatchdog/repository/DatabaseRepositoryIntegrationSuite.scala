package dbwatchdog.repository

import java.util.UUID

import dbwatchdog.domain.CreateDatabase
import dbwatchdog.support.PostgresIntegrationSuite

object DatabaseRepositoryIntegrationSuite extends PostgresIntegrationSuite {
  private val repo = DatabaseRepository.make

  test("insert persists a database record") { db =>
    withCleanDb(db) { db =>
      val suffix = UUID.randomUUID().toString.take(8)
      val input = CreateDatabase(
        engine = "postgres",
        host = s"db-$suffix.local",
        port = 5432,
        user = s"db_user_$suffix",
        password = "secret",
        schema = "public"
      )

      for {
        persisted <- db.transact(repo.insert(input))
      } yield expect(persisted.engine == input.engine) and
        expect(persisted.host == input.host) and
        expect(persisted.port == input.port) and
        expect(persisted.user == input.user) and
        expect(persisted.schema == input.schema)
    }
  }
}
