package dbwatchdog.database

import cats.effect.IO

import dbwatchdog.support.{IntegrationDb, PostgresIntegrationSuite}
import weaver.Expectations

object MigrationIntegrationSuite extends PostgresIntegrationSuite {

  test("migrations create the required tables") { db =>
    assertRequiredTables(db)
  }

  private def assertRequiredTables(db: IntegrationDb): IO[Expectations] =
    for {
      users <- db.tableExists("users")
      teams <- db.tableExists("teams")
      databases <- db.tableExists("databases")
    } yield expect(users) and expect(teams) and expect(databases)
}
