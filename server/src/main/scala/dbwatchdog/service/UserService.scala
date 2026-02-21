package dbwatchdog.service

import cats.effect.IO

import dbwatchdog.database.Database
import dbwatchdog.domain.{UpdateUserRequest, User}
import dbwatchdog.repository.UserRepository

trait UserService {

  def syncUser(
      keycloakId: String,
      email: String,
      firstName: String,
      lastName: String,
      team: String
  ): IO[User]

  def getUserByKeycloakId(keycloackId: String): IO[User]

  def updateUser(keycloakId: String, request: UpdateUserRequest): IO[User]
}

object UserService {

  def make(repository: UserRepository, db: Database): UserService =
    new UserService {

      def syncUser(
          keycloakId: String,
          email: String,
          firstName: String,
          lastName: String,
          team: String
      ): IO[dbwatchdog.domain.User] =
        db.transact(
          repository
            .upsert(keycloakId, email, firstName, lastName, team)
        )

      def getUserByKeycloakId(keycloackId: String): IO[User] =
        db.transact(
          repository.findByKeycloakId(keycloackId)
        )

      def updateUser(keycloakId: String, request: UpdateUserRequest): IO[User] =
        db.transact(
          for {
            user <- repository.findByKeycloakId(keycloakId)
            result <- repository.update(
              user.id,
              request.firstName.getOrElse(user.firstName),
              request.lastName.getOrElse(user.lastName),
              request.team.getOrElse(user.team)
            )
          } yield result
        )
    }
}
