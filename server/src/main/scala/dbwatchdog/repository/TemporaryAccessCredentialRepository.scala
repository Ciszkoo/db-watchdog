package dbwatchdog.repository

import java.time.Instant
import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.{
  CreateTemporaryAccessCredentialInput,
  TemporaryAccessCredential
}

trait TemporaryAccessCredentialRepository
    extends TableFragment[UUID, TemporaryAccessCredential] {
  val tableName = "temporary_access_credentials"
  val columns = List(
    "id",
    "user_id",
    "database_id",
    "otp_hash",
    "expires_at",
    "used_at",
    "created_at",
    "updated_at"
  )

  def create(
      input: CreateTemporaryAccessCredentialInput
  ): ConnectionIO[TemporaryAccessCredential]

  def markUsed(
      id: UUID,
      usedAt: Instant
  ): ConnectionIO[TemporaryAccessCredential]

  def invalidateActiveForUserAndDatabase(
      userId: UUID,
      databaseId: UUID,
      now: Instant
  ): ConnectionIO[Int]

  def invalidateActiveForDatabase(
      databaseId: UUID,
      now: Instant
  ): ConnectionIO[Int]
}

object TemporaryAccessCredentialRepository {

  def make: TemporaryAccessCredentialRepository =
    new TemporaryAccessCredentialRepository {
      def create(
          input: CreateTemporaryAccessCredentialInput
      ): ConnectionIO[TemporaryAccessCredential] =
        (fr"""
          INSERT INTO temporary_access_credentials (user_id, database_id, otp_hash, expires_at)
          VALUES (${input.userId}, ${input.databaseId}, ${input.otpHash}, ${input.expiresAt})
        """ ++ returningF)
          .query[TemporaryAccessCredential]
          .unique

      def markUsed(
          id: UUID,
          usedAt: Instant
      ): ConnectionIO[TemporaryAccessCredential] =
        (fr"""
          UPDATE temporary_access_credentials SET
            used_at = $usedAt,
            updated_at = NOW()
          WHERE id = $id
        """ ++ returningF)
          .query[TemporaryAccessCredential]
          .unique

      def invalidateActiveForUserAndDatabase(
          userId: UUID,
          databaseId: UUID,
          now: Instant
      ): ConnectionIO[Int] =
        sql"""
          UPDATE temporary_access_credentials SET
            used_at = $now,
            updated_at = NOW()
          WHERE user_id = $userId
            AND database_id = $databaseId
            AND used_at IS NULL
            AND expires_at > $now
        """.update.run

      def invalidateActiveForDatabase(
          databaseId: UUID,
          now: Instant
      ): ConnectionIO[Int] =
        sql"""
          UPDATE temporary_access_credentials SET
            used_at = $now,
            updated_at = NOW()
          WHERE database_id = $databaseId
            AND used_at IS NULL
            AND expires_at > $now
        """.update.run
    }
}
