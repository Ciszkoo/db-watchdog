package dbwatchdog.service

import dbwatchdog.config.AppConfig
import dbwatchdog.database.Database
import dbwatchdog.repository.Repositories

case class Services(
    users: UserService,
    admin: AdminService,
    access: AccessService
)

object Services {
  def make(repos: Repositories, db: Database)(using
      config: AppConfig
  ): Services =
    val userService = UserService.make(repos.users, repos.teams, db)

    Services(
      users = userService,
      admin = AdminService.make(repos, db),
      access = AccessService.make(repos, userService, db)
    )
}
