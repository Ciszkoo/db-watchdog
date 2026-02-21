package dbwatchdog.repository

import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.{UpsertUserInput, User}

trait UserRepository extends TableFragment[UUID, User] {
  val tableName = "users"
  val columns = List(
    "id",
    "keycloak_id",
    "email",
    "first_name",
    "last_name",
    "team_id",
    "created_at",
    "updated_at"
  )

  def create(
      keycloakId: String,
      email: String,
      firstName: String,
      lastName: String
  ): ConnectionIO[User]

  def update(
      id: UUID,
      firstName: String,
      lastName: String,
      team: String
  ): ConnectionIO[User]

  def upsert(
      input: UpsertUserInput,
      teamId: UUID
  ): ConnectionIO[User]

  def findByKeycloakId(keycloakId: String): ConnectionIO[User]
}

object UserRepository {

  def make: UserRepository = new UserRepository {
    def create(
        keycloakId: String,
        email: String,
        firstName: String,
        lastName: String
    ): ConnectionIO[User] =
      (fr"INSERT INTO users" ++ insertColsF ++
        fr"VALUES ($keycloakId, $email, $firstName, $lastName, NOW(), NOW())").update
        .withUniqueGeneratedKeys[User](columns*)

    def update(
        id: UUID,
        firstName: String,
        lastName: String,
        team: String
    ): ConnectionIO[User] =
      (fr"UPDATE users SET" ++
        fr"first_name = $firstName, last_name = $lastName, team = $team, updated_at = NOW()" ++
        Fragments.whereAnd(fr"id = $id") ++ returningF)
        .query[User]
        .unique

    def upsert(
        input: UpsertUserInput,
        teamId: UUID
    ): ConnectionIO[User] = {
      val insertFragment =
        fr"""
        INSERT INTO users (keycloak_id, email, first_name, last_name, team_id)
        VALUES (
          ${input.keycloakId},
          ${input.email},
          ${input.firstName},
          ${input.lastName},
          $teamId
        )
      """

      val onConflictFragment =
        fr"""
        ON CONFLICT (keycloak_id) DO UPDATE SET
          email      = EXCLUDED.email,
          first_name = EXCLUDED.first_name,
          last_name  = EXCLUDED.last_name,
          team_id    = EXCLUDED.team_id,
          updated_at = NOW()
      """

      (insertFragment ++ onConflictFragment ++ returningF)
        .query[User]
        .unique
    }

    def findByKeycloakId(keycloakId: String): ConnectionIO[User] =
      (selectF ++ fr"WHERE users.keycloak_id = $keycloakId")
        .query[User]
        .unique
  }
}
