package dbwatchdog.service

import cats.effect.IO

import dbwatchdog.database.Database
import dbwatchdog.domain.{UpsertUserInput, User}
import dbwatchdog.repository.{TeamRepository, UserRepository}

trait UserService {

  def syncUser(
      input: UpsertUserInput
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
          input: UpsertUserInput
      ): IO[dbwatchdog.domain.User] =
        db.transact(
          for {
            team <- teamRepo.findOrCreate(input.teamName)
            user <- userRepo.upsert(input, team.id)
          } yield user
        )

      def getUserByKeycloakId(keycloackId: String): IO[User] =
        db.transact(
          userRepo.findByKeycloakId(keycloackId)
        )
    }
}
