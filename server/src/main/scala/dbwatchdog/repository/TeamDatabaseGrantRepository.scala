package dbwatchdog.repository

import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.{TeamDatabaseGrant, UpsertTeamDatabaseGrantInput}

trait TeamDatabaseGrantRepository
    extends TableFragment[UUID, TeamDatabaseGrant] {
  val tableName = "team_database_grants"
  val columns = List(
    "id",
    "team_id",
    "database_id",
    "created_at",
    "updated_at"
  )

  def upsert(
      input: UpsertTeamDatabaseGrantInput
  ): ConnectionIO[TeamDatabaseGrant]
}

object TeamDatabaseGrantRepository {

  def make: TeamDatabaseGrantRepository = new TeamDatabaseGrantRepository {
    def upsert(
        input: UpsertTeamDatabaseGrantInput
    ): ConnectionIO[TeamDatabaseGrant] =
      (fr"""
        INSERT INTO team_database_grants (team_id, database_id)
        VALUES (${input.teamId}, ${input.databaseId})
        ON CONFLICT (team_id, database_id) DO UPDATE SET
          updated_at = NOW()
      """ ++ returningF)
        .query[TeamDatabaseGrant]
        .unique
  }
}
