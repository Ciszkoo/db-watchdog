package dbwatchdog.repository

import java.time.Instant
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

  def list: ConnectionIO[List[UserDatabaseAccessExtension]]

  def delete(
      userId: UUID,
      databaseId: UUID
  ): ConnectionIO[Int]

  def findActiveByUserId(
      userId: UUID,
      now: Instant
  ): ConnectionIO[List[UserDatabaseAccessExtension]]
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

      def list: ConnectionIO[List[UserDatabaseAccessExtension]] =
        (selectF ++ fr"ORDER BY user_database_access_extensions.user_id, user_database_access_extensions.database_id, user_database_access_extensions.id")
          .query[UserDatabaseAccessExtension]
          .to[List]

      def delete(
          userId: UUID,
          databaseId: UUID
      ): ConnectionIO[Int] =
        sql"""
          DELETE FROM user_database_access_extensions
          WHERE user_id = $userId AND database_id = $databaseId
        """.update.run

      def findActiveByUserId(
          userId: UUID,
          now: Instant
      ): ConnectionIO[List[UserDatabaseAccessExtension]] =
        sql"""
          SELECT
            user_database_access_extensions.id,
            user_database_access_extensions.user_id,
            user_database_access_extensions.database_id,
            user_database_access_extensions.expires_at,
            user_database_access_extensions.created_at,
            user_database_access_extensions.updated_at
          FROM user_database_access_extensions
          WHERE user_id = $userId
            AND (expires_at IS NULL OR expires_at > $now)
          ORDER BY database_id
        """.query[UserDatabaseAccessExtension].to[List]
    }
}
