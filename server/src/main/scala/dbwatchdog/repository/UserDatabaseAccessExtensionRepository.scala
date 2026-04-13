package dbwatchdog.repository

import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.{
  UpsertUserDatabaseAccessExtensionInput,
  UserDatabaseAccessExtension
}

trait UserDatabaseAccessExtensionRepository
    extends TableFragment[UUID, UserDatabaseAccessExtension] {
  val tableName = "user_database_access_extensions"
  val columns = List(
    "id",
    "user_id",
    "database_id",
    "expires_at",
    "created_at",
    "updated_at"
  )

  def upsert(
      input: UpsertUserDatabaseAccessExtensionInput
  ): ConnectionIO[UserDatabaseAccessExtension]
}

object UserDatabaseAccessExtensionRepository {

  def make: UserDatabaseAccessExtensionRepository =
    new UserDatabaseAccessExtensionRepository {
      def upsert(
          input: UpsertUserDatabaseAccessExtensionInput
      ): ConnectionIO[UserDatabaseAccessExtension] =
        (fr"""
          INSERT INTO user_database_access_extensions (user_id, database_id, expires_at)
          VALUES (${input.userId}, ${input.databaseId}, ${input.expiresAt})
          ON CONFLICT (user_id, database_id) DO UPDATE SET
            expires_at = EXCLUDED.expires_at,
            updated_at = NOW()
        """ ++ returningF)
          .query[UserDatabaseAccessExtension]
          .unique
    }
}
