package dbwatchdog.repository

import java.util.UUID

import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.config.AppConfig
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
    "technical_password_ciphertext",
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

  def rewrapTechnicalCredentials(): ConnectionIO[Int]

  def list: ConnectionIO[List[Database]]

  def findById(id: UUID): ConnectionIO[Option[Database]]

  def findByIds(ids: Set[UUID]): ConnectionIO[List[Database]]

  def findActiveById(id: UUID): ConnectionIO[Option[Database]]

  def findActiveByIds(ids: Set[UUID]): ConnectionIO[List[Database]]
}

object DatabaseRepository {

  def make(using config: AppConfig): DatabaseRepository =
    new DatabaseRepository {
      private val schemaName = config.db.schema

      private val returningDatabaseF =
        fr"""
        RETURNING id,
                  engine,
                  host,
                  port,
                  technical_user,
      """ ++ decryptTechnicalPasswordF(
          "technical_password_ciphertext"
        ) ++ fr""",
                  database_name,
                  created_at,
                  updated_at,
                  deactivated_at
      """

      private val selectDatabaseF =
        fr"""
        SELECT databases.id,
               databases.engine,
               databases.host,
               databases.port,
               databases.technical_user,
      """ ++ decryptTechnicalPasswordF(
          "databases.technical_password_ciphertext"
        ) ++
          fr""",
               databases.database_name,
               databases.created_at,
               databases.updated_at,
               databases.deactivated_at
        FROM
      """ ++ tableF

      private def decryptTechnicalPasswordF(columnName: String): Fragment =
        Fragment.const(s"$schemaName.decrypt_technical_password(") ++
          Fragment.const(columnName) ++ fr")"

      private def encryptedPasswordF(password: String): Fragment =
        Fragment.const(s"$schemaName.encrypt_technical_password(") ++
          fr"$password)"

      private def technicalPasswordNeedsRewrapF(
          columnName: String
      ): Fragment =
        Fragment.const(s"$schemaName.technical_password_needs_rewrap(") ++
          Fragment.const(columnName) ++ fr")"

      private def rewrapTechnicalPasswordF(columnName: String): Fragment =
        Fragment.const(s"$schemaName.rewrap_technical_password(") ++
          Fragment.const(columnName) ++ fr")"

      def insert(input: CreateDatabase): ConnectionIO[Database] =
        (fr"""
          INSERT INTO
        """ ++ tableF ++ fr"""
          (
            engine,
            host,
            port,
            technical_user,
            technical_password_ciphertext,
            database_name
          )
          VALUES (
            ${input.engine},
            ${input.host},
            ${input.port},
            ${input.technicalUser},
        """ ++ encryptedPasswordF(input.technicalPassword) ++ fr""",
            ${input.databaseName}
          )
        """ ++ returningDatabaseF)
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
              technical_password_ciphertext =
        """ ++ encryptedPasswordF(input.technicalPassword) ++ fr""",
              database_name = ${input.databaseName},
              updated_at = NOW()
          WHERE id = $id
        """ ++ returningDatabaseF)
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
        """ ++ returningDatabaseF)
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
        """ ++ returningDatabaseF)
          .query[Database]
          .unique

      def rewrapTechnicalCredentials(): ConnectionIO[Int] =
        (fr"""
          UPDATE
        """ ++ tableF ++ fr"""
          SET technical_password_ciphertext =
        """ ++ rewrapTechnicalPasswordF("technical_password_ciphertext") ++
          fr""",
              updated_at = NOW()
          WHERE
        """ ++ technicalPasswordNeedsRewrapF(
            "technical_password_ciphertext"
          )).update.run

      def list: ConnectionIO[List[Database]] =
        (selectDatabaseF ++
          fr"ORDER BY databases.database_name ASC, databases.host ASC, databases.port ASC")
          .query[Database]
          .to[List]

      def findById(id: UUID): ConnectionIO[Option[Database]] =
        (selectDatabaseF ++ fr"WHERE databases.id = $id").query[Database].option

      def findByIds(ids: Set[UUID]): ConnectionIO[List[Database]] =
        ids.toList.toNel match
          case None              => List.empty[Database].pure[ConnectionIO]
          case Some(nonEmptyIds) =>
            (selectDatabaseF ++
              fr"WHERE" ++
              Fragments.in(fr"databases.id", nonEmptyIds))
              .query[Database]
              .to[List]

      def findActiveById(id: UUID): ConnectionIO[Option[Database]] =
        (
          selectDatabaseF ++
            fr"WHERE databases.id = $id AND databases.deactivated_at IS NULL"
        )
          .query[Database]
          .option

      def findActiveByIds(ids: Set[UUID]): ConnectionIO[List[Database]] =
        ids.toList.toNel match
          case None              => List.empty[Database].pure[ConnectionIO]
          case Some(nonEmptyIds) =>
            (
              selectDatabaseF ++
                fr"WHERE databases.deactivated_at IS NULL AND" ++
                Fragments.in(fr"databases.id", nonEmptyIds)
            )
              .query[Database]
              .to[List]
    }
}
