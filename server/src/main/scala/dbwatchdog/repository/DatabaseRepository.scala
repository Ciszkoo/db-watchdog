package dbwatchdog.repository

import java.util.UUID

import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.{CreateDatabase, Database}

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
    "updated_at"
  )

  def insert(input: CreateDatabase): ConnectionIO[Database]

  def list: ConnectionIO[List[Database]]

  def findById(id: UUID): ConnectionIO[Option[Database]]

  def findByIds(ids: Set[UUID]): ConnectionIO[List[Database]]
}

object DatabaseRepository {

  def make: DatabaseRepository = new DatabaseRepository {

    def insert(input: CreateDatabase): ConnectionIO[Database] =
      (fr"INSERT INTO" ++ tableF ++ fr"(engine, host, port, technical_user, technical_password, database_name)" ++
        fr"VALUES (${input.engine}, ${input.host}, ${input.port}, ${input.technicalUser}, ${input.technicalPassword}, ${input.databaseName})" ++
        returningF)
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
  }
}
