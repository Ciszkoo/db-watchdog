package dbwatchdog.repository

case class Repositories(
    users: UserRepository,
    teams: TeamRepository,
    databases: DatabaseRepository,
    teamDatabaseGrants: TeamDatabaseGrantRepository,
    userDatabaseAccessExtensions: UserDatabaseAccessExtensionRepository,
    temporaryAccessCredentials: TemporaryAccessCredentialRepository,
    databaseSessions: DatabaseSessionRepository
)

object Repositories {

  def make: Repositories =
    Repositories(
      users = UserRepository.make,
      teams = TeamRepository.make,
      databases = DatabaseRepository.make,
      teamDatabaseGrants = TeamDatabaseGrantRepository.make,
      userDatabaseAccessExtensions = UserDatabaseAccessExtensionRepository.make,
      temporaryAccessCredentials = TemporaryAccessCredentialRepository.make,
      databaseSessions = DatabaseSessionRepository.make
    )
}
