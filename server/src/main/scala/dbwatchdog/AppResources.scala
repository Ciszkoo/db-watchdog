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
    xa <- makePostgresTransactor(appConfig.db)
  } yield AppResources(Database.make(using xa))

  private def makePostgresTransactor(
      config: AppConfig.DatabaseConfig
  ): Resource[IO, Transactor[IO]] = {
    val hikariConf = HikariConfig()

    DriverManager.registerDriver(org.postgresql.Driver())

    hikariConf.setDriverClassName("org.postgresql.Driver")
    hikariConf.setJdbcUrl(config.url)
    hikariConf.setUsername(config.user)
    hikariConf.setPassword(config.password)
    hikariConf.setSchema(config.schema)
    hikariConf.setMaximumPoolSize(config.threadPoolSize)

    HikariTransactor.fromHikariConfig[IO](hikariConfig = hikariConf)
  }
}
