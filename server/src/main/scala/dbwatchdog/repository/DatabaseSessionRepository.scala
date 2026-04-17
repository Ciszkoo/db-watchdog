package dbwatchdog.repository

import java.time.Instant
import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.{
  AdminDatabaseSessionState,
  CreateDatabaseSessionInput,
  DatabaseSession,
  ListAdminSessionsQuery
}

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

  def listPage(
      query: ListAdminSessionsQuery
  ): ConnectionIO[List[DatabaseSession]]

  def count(query: ListAdminSessionsQuery): ConnectionIO[Long]

  def markEnded(
      id: UUID,
      endedAt: Instant,
      bytesSent: Long,
      bytesReceived: Long
  ): ConnectionIO[DatabaseSession]
}

object DatabaseSessionRepository {
  private val fromWithUsersF =
    fr"""
      FROM database_sessions
      INNER JOIN users ON users.id = database_sessions.user_id
    """

  private def filtersF(query: ListAdminSessionsQuery): Fragment =
    Fragments.whereAndOpt(
      query.userId.map(userId => fr"database_sessions.user_id = $userId"),
      query.teamId.map(teamId => fr"users.team_id = $teamId"),
      query.databaseId.map(databaseId =>
        fr"database_sessions.database_id = $databaseId"
      ),
      query.state match {
        case AdminDatabaseSessionState.All  => None
        case AdminDatabaseSessionState.Open =>
          Some(fr"database_sessions.ended_at IS NULL")
        case AdminDatabaseSessionState.Closed =>
          Some(fr"database_sessions.ended_at IS NOT NULL")
      },
      query.startedFrom.map(startedFrom =>
        fr"database_sessions.started_at >= $startedFrom"
      ),
      query.startedTo.map(startedTo =>
        fr"database_sessions.started_at < $startedTo"
      )
    )

  def make: DatabaseSessionRepository = new DatabaseSessionRepository {
    def create(
        input: CreateDatabaseSessionInput
    ): ConnectionIO[DatabaseSession] =
      (fr"""
        INSERT INTO database_sessions (user_id, database_id, credential_id, client_addr, started_at)
        VALUES (${input.userId}, ${input.databaseId}, ${input.credentialId}, ${input.clientAddr}, ${input.startedAt})
      """ ++ returningF)
        .query[DatabaseSession]
        .unique

    def listPage(
        query: ListAdminSessionsQuery
    ): ConnectionIO[List[DatabaseSession]] =
      (fr"SELECT " ++ columnsFullF ++ fromWithUsersF ++ filtersF(query) ++
        fr"""
          ORDER BY database_sessions.started_at DESC, database_sessions.id DESC
          LIMIT ${query.pageSize}
          OFFSET ${query.offset}
        """)
        .query[DatabaseSession]
        .to[List]

    def count(query: ListAdminSessionsQuery): ConnectionIO[Long] =
      (fr"SELECT COUNT(*)" ++ fromWithUsersF ++ filtersF(query))
        .query[Long]
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
