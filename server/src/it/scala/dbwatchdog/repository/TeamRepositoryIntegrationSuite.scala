package dbwatchdog.repository

import java.util.UUID

import dbwatchdog.support.PostgresIntegrationSuite

object TeamRepositoryIntegrationSuite extends PostgresIntegrationSuite {
  private val repo = TeamRepository.make

  test("create inserts a team") { db =>
    withCleanDb(db) { db =>
      val teamName = uniqueTeamName("backend")

      for {
        created <- db.transact(repo.create(teamName))
      } yield expect(created.name == teamName)
    }
  }

  test("findByName returns an existing team") { db =>
    withCleanDb(db) { db =>
      val teamName = uniqueTeamName("backend")

      for {
        created <- db.transact(repo.create(teamName))
        found <- db.transact(repo.findByName(teamName))
      } yield expect(found.contains(created))
    }
  }

  test("findOrCreate inserts a team when it does not exist") { db =>
    withCleanDb(db) { db =>
      val teamName = uniqueTeamName("backend")

      for {
        created <- db.transact(repo.findOrCreate(teamName))
      } yield expect(created.name == teamName)
    }
  }

  test("findOrCreate returns the existing team when it already exists") { db =>
    withCleanDb(db) { db =>
      val teamName = uniqueTeamName("backend")

      for {
        existing <- db.transact(repo.create(teamName))
        reused <- db.transact(repo.findOrCreate(teamName))
      } yield expect(reused.id == existing.id)
    }
  }

  private def uniqueTeamName(prefix: String): String =
    s"$prefix-${UUID.randomUUID().toString.take(8)}"
}
