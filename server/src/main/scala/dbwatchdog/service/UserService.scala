package dbwatchdog.service

import cats.effect.IO

import dbwatchdog.database.Database
import dbwatchdog.domain.{AuthenticatedUserSyncInput, UpsertUserInput, User}
import dbwatchdog.repository.{TeamRepository, UserRepository}

trait UserService {

  def syncUser(
      input: AuthenticatedUserSyncInput
  ): IO[User]

  def getUserByKeycloakId(keycloackId: String): IO[User]
}

object UserService {

  def make(
      userRepo: UserRepository,
      teamRepo: TeamRepository,
      db: Database
  ): UserService =
    new UserService {

      def syncUser(
          input: AuthenticatedUserSyncInput
      ): IO[dbwatchdog.domain.User] =
        db.transact(
          for {
            team <- teamRepo.findOrCreate(input.team)
            user <- userRepo.upsert(
              UpsertUserInput(
                keycloakId = input.keycloakId,
                email = input.email,
                firstName = input.firstName,
                lastName = input.lastName,
                teamName = input.team
              ),
              team.id
            )
          } yield user
        )

      def getUserByKeycloakId(keycloackId: String): IO[User] =
        db.transact(
          userRepo.findByKeycloakId(keycloackId)
        )
    }
}
