package dbwatchdog.repository

import java.time.Instant
import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.{CreateDatabaseSessionInput, DatabaseSession}

trait DatabaseSessionRepository extends TableFragment[UUID, DatabaseSession] {
  val tableName = "database_sessions"
  val columns = List(
    "id",
    "user_id",
    "database_id",
    "credential_id",
    "client_addr",
    "started_at",
    "ended_at",
    "bytes_sent",
    "bytes_received",
    "created_at",
    "updated_at"
  )

  def create(input: CreateDatabaseSessionInput): ConnectionIO[DatabaseSession]

  def markEnded(
      id: UUID,
      endedAt: Instant,
      bytesSent: Long,
      bytesReceived: Long
  ): ConnectionIO[DatabaseSession]
}

object DatabaseSessionRepository {

  def make: DatabaseSessionRepository = new DatabaseSessionRepository {
    def create(input: CreateDatabaseSessionInput): ConnectionIO[DatabaseSession] =
      (fr"""
        INSERT INTO database_sessions (user_id, database_id, credential_id, client_addr, started_at)
        VALUES (${input.userId}, ${input.databaseId}, ${input.credentialId}, ${input.clientAddr}, ${input.startedAt})
      """ ++ returningF)
        .query[DatabaseSession]
        .unique

    def markEnded(
        id: UUID,
        endedAt: Instant,
        bytesSent: Long,
        bytesReceived: Long
    ): ConnectionIO[DatabaseSession] =
      (fr"""
        UPDATE database_sessions SET
          ended_at = $endedAt,
          bytes_sent = $bytesSent,
          bytes_received = $bytesReceived,
          updated_at = NOW()
        WHERE id = $id
      """ ++ returningF)
        .query[DatabaseSession]
        .unique
  }
}
