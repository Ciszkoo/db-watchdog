package dbwatchdog.repository

import java.util.UUID

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
    "\"user\"",
    "password",
    "\"schema\"",
    "created_at",
    "updated_at"
  )

  def insert(input: CreateDatabase): ConnectionIO[Database]
}

object DatabaseRepository {

  def make: DatabaseRepository = new DatabaseRepository {

    def insert(input: CreateDatabase): ConnectionIO[Database] =
      (fr"INSERT INTO" ++ tableF ++ fr"(engine, host, port, \"user\", password, \"schema\")" ++
        fr"VALUES (${input.engine}, ${input.host}, ${input.port}, ${input.user}, ${input.password}, ${input.schema})" ++
        returningF)
        .query[Database]
        .unique
  }
}
