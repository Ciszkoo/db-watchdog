package dbwatchdog.repository

import java.util.UUID

import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.database.TableFragment
import dbwatchdog.domain.Team

trait TeamRepository extends TableFragment[UUID, Team] {

  val tableName: String = "teams"
  val columns: List[String] = List("id", "name", "created_at", "updated_at")

  def create(name: String): ConnectionIO[Team]
  def findByName(name: String): ConnectionIO[Option[Team]]
  def findOrCreate(name: String): ConnectionIO[Team]
}

object TeamRepository {

  def make: TeamRepository = new TeamRepository {

    def create(name: String): ConnectionIO[Team] =
      (fr"INSERT INTO" ++ tableF ++ fr"(name) VALUES ($name)"
        ++ returningF).query[Team].unique

    def findByName(name: String): ConnectionIO[Option[Team]] =
      (selectF ++ fr"WHERE name = $name").query[Team].option

    def findOrCreate(name: String): ConnectionIO[Team] = {
      val insert = (fr"INSERT INTO" ++ tableF ++ fr"(name) VALUES ($name)"
        ++ fr"ON CONFLICT (name) DO NOTHING"
        ++ returningF).query[Team].option

      val select = (selectF ++ fr"WHERE name = $name").query[Team].unique

      insert.flatMap { team =>
        team match
          case Some(team) => team.pure[ConnectionIO]
          case None       => select
      }
    }
  }
}
