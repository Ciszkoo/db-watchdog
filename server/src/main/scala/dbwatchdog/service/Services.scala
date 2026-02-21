package dbwatchdog.service

import dbwatchdog.database.Database
import dbwatchdog.repository.Repositories

case class Services(users: UserService)

object Services {
  def make(repos: Repositories, db: Database): Services =
    Services(users = UserService.make(repos.users, db))
}
