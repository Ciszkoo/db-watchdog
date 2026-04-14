package dbwatchdog.repository

import java.util.UUID

import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.{CreateDatabase, Database, UpdateDatabase}

trait DatabaseRepository extends TableFragment[UUID, Database] {

  val tableName: String = "databases"
  val columns: List[String] = List(
    "id",
    "engine",
    "host",
    "port",
    "technical_user",
    "technical_password",
    "database_name",
    "created_at",
    "updated_at",
    "deactivated_at"
  )

  def insert(input: CreateDatabase): ConnectionIO[Database]

  def update(
      id: UUID,
      input: UpdateDatabase
  ): ConnectionIO[Database]

  def deactivate(
      id: UUID,
      now: java.time.Instant
  ): ConnectionIO[Database]

  def reactivate(id: UUID): ConnectionIO[Database]

  def list: ConnectionIO[List[Database]]

  def findById(id: UUID): ConnectionIO[Option[Database]]

  def findByIds(ids: Set[UUID]): ConnectionIO[List[Database]]

  def findActiveById(id: UUID): ConnectionIO[Option[Database]]

  def findActiveByIds(ids: Set[UUID]): ConnectionIO[List[Database]]
}

object DatabaseRepository {

  def make: DatabaseRepository = new DatabaseRepository {

    def insert(input: CreateDatabase): ConnectionIO[Database] =
      (fr"INSERT INTO" ++ tableF ++ fr"(engine, host, port, technical_user, technical_password, database_name)" ++
        fr"VALUES (${input.engine}, ${input.host}, ${input.port}, ${input.technicalUser}, ${input.technicalPassword}, ${input.databaseName})" ++
        returningF)
        .query[Database]
        .unique

    def update(
        id: UUID,
        input: UpdateDatabase
    ): ConnectionIO[Database] =
      (fr"""
        UPDATE
      """ ++ tableF ++ fr"""
        SET engine = ${input.engine},
            host = ${input.host},
            port = ${input.port},
            technical_user = ${input.technicalUser},
            technical_password = ${input.technicalPassword},
            database_name = ${input.databaseName},
            updated_at = NOW()
        WHERE id = $id
      """ ++ returningF)
        .query[Database]
        .unique

    def deactivate(
        id: UUID,
        now: java.time.Instant
    ): ConnectionIO[Database] =
      (fr"""
        UPDATE
      """ ++ tableF ++ fr"""
        SET deactivated_at = CASE
              WHEN deactivated_at IS NULL THEN $now
              ELSE deactivated_at
            END,
            updated_at = CASE
              WHEN deactivated_at IS NULL THEN NOW()
              ELSE updated_at
            END
        WHERE id = $id
      """ ++ returningF)
        .query[Database]
        .unique

    def reactivate(id: UUID): ConnectionIO[Database] =
      (fr"""
        UPDATE
      """ ++ tableF ++ fr"""
        SET deactivated_at = NULL,
            updated_at = CASE
              WHEN deactivated_at IS NULL THEN updated_at
              ELSE NOW()
            END
        WHERE id = $id
      """ ++ returningF)
        .query[Database]
        .unique

    def list: ConnectionIO[List[Database]] =
      (selectF ++ fr"ORDER BY databases.database_name ASC, databases.host ASC, databases.port ASC")
        .query[Database]
        .to[List]

    def findById(id: UUID): ConnectionIO[Option[Database]] =
      (selectF ++ fr"WHERE databases.id = $id").query[Database].option

    def findByIds(ids: Set[UUID]): ConnectionIO[List[Database]] =
      ids.toList.toNel match
        case None              => List.empty[Database].pure[ConnectionIO]
        case Some(nonEmptyIds) =>
          (selectF ++ fr"WHERE" ++ Fragments.in(fr"databases.id", nonEmptyIds))
            .query[Database]
            .to[List]

    def findActiveById(id: UUID): ConnectionIO[Option[Database]] =
      (selectF ++ fr"WHERE databases.id = $id AND databases.deactivated_at IS NULL")
        .query[Database]
        .option

    def findActiveByIds(ids: Set[UUID]): ConnectionIO[List[Database]] =
      ids.toList.toNel match
        case None              => List.empty[Database].pure[ConnectionIO]
        case Some(nonEmptyIds) =>
          (
            selectF ++
              fr"WHERE databases.deactivated_at IS NULL AND" ++
              Fragments.in(fr"databases.id", nonEmptyIds)
          )
            .query[Database]
            .to[List]
  }
}
