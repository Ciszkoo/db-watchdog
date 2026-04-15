package dbwatchdog.database

import cats.effect.IO
import cats.effect.kernel.Resource
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Locations, MigrateResult, MigrationVersion}

import dbwatchdog.config.AppConfig

object Migration {
  def migrate(
      targetVersion: Option[MigrationVersion] = None
  )(using config: AppConfig): IO[MigrateResult] = {
    makeFlyway(targetVersion).use { flyway =>
      flyway.migrate
    }
  }

  private def makeFlyway(
      targetVersion: Option[MigrationVersion]
  )(using config: AppConfig): Resource[IO, Fly4s[IO]] = {
    Fly4s.make[IO](
      url = config.db.url,
      user = Some(config.db.user),
      password = Some(config.db.password.toArray),
      config = Fly4sConfig(
        initSql = Some(config.credentialEncryption.sessionInitSql),
        locations = Locations("db/migration"),
        baselineOnMigrate = true,
        cleanDisabled = true,
        defaultSchemaName = Some(config.db.schema),
        targetVersion = targetVersion.getOrElse(MigrationVersion.latest)
      )
    )
  }
}
