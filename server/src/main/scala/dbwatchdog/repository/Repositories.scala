package dbwatchdog.repository

case class Repositories(users: UserRepository)

object Repositories {
  def make: Repositories =
    Repositories(users = UserRepository.make)
}
