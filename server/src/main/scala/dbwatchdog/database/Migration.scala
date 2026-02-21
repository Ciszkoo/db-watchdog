package dbwatchdog.database

import cats.effect.IO
import cats.effect.kernel.Resource
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Locations, MigrateResult}

import dbwatchdog.config.AppConfig

object Migration {
  def migrate(using config: AppConfig): IO[MigrateResult] = {
    makeFlyway.use { flyway =>
      flyway.migrate
    }
  }

  private def makeFlyway(using config: AppConfig): Resource[IO, Fly4s[IO]] = {
    Fly4s.make[IO](
      url = config.db.url,
      user = Some(config.db.user),
      password = Some(config.db.password.toArray),
      config = Fly4sConfig(
        locations = Locations("db/migration"),
        baselineOnMigrate = true,
        cleanDisabled = true,
        defaultSchemaName = Some(config.db.schema)
      )
    )
  }
}
