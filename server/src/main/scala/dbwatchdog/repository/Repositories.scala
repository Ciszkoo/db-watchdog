package dbwatchdog.repository

case class Repositories(users: UserRepository, teams: TeamRepository)

object Repositories {

  def make: Repositories =
    Repositories(users = UserRepository.make, teams = TeamRepository.make)
}
