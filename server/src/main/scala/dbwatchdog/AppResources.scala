package dbwatchdog

import java.sql.DriverManager

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor
import doobie.util.transactor.Transactor

import dbwatchdog.config.AppConfig
import dbwatchdog.database.Database

case class AppResources(
    db: Database
)

object AppResources {

  def make(using appConfig: AppConfig): Resource[IO, AppResources] = for {
    xa <- makePostgresTransactor(appConfig)
  } yield AppResources(Database.make(using xa))

  private[dbwatchdog] def makePostgresTransactor(
      config: AppConfig
  ): Resource[IO, Transactor[IO]] = {
    val hikariConf = HikariConfig()

    DriverManager.registerDriver(org.postgresql.Driver())

    hikariConf.setDriverClassName("org.postgresql.Driver")
    hikariConf.setJdbcUrl(config.db.url)
    hikariConf.setUsername(config.db.user)
    hikariConf.setPassword(config.db.password)
    hikariConf.setSchema(config.db.schema)
    hikariConf.setMaximumPoolSize(config.db.threadPoolSize)
    hikariConf.setConnectionInitSql(config.credentialEncryption.sessionInitSql)

    HikariTransactor.fromHikariConfig[IO](hikariConfig = hikariConf)
  }
}
