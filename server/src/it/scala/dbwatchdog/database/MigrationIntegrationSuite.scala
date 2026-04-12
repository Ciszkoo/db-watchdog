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
}
