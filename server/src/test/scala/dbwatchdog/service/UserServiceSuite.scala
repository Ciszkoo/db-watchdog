package dbwatchdog.service

import java.util.UUID

import cats.effect.IO
import cats.implicits.*
import cats.~>
import doobie.ConnectionIO
import doobie.free.connection.ConnectionOp
import weaver.SimpleIOSuite

import dbwatchdog.database.Database
import dbwatchdog.domain.{AuthenticatedUserSyncInput, Team, UpsertUserInput}
import dbwatchdog.repository.{TeamRepository, UserRepository}
import dbwatchdog.support.AuthTestSupport

object UserServiceSuite extends SimpleIOSuite {

  test("syncUser finds or creates the team and upserts the user") {
    val input = AuthenticatedUserSyncInput(
      keycloakId = "kc-123",
      email = "john@example.com",
      firstName = "John",
      lastName = "Doe",
      team = "backend"
    )

    val teamId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    var observedTeamName: Option[String] = None
    var observedUpsert: Option[(UpsertUserInput, UUID)] = None

    val teamRepo = new TeamRepository {
      override val tableName = "teams"
      override val columns = Nil

      def create(name: String) = Team(
        teamId,
        name,
        AuthTestSupport.persistedUser.createdAt,
        AuthTestSupport.persistedUser.updatedAt
      ).pure[ConnectionIO]

      def findByName(name: String) = Option.empty[Team].pure[ConnectionIO]

      def findOrCreate(name: String) = {
        observedTeamName = Some(name)
        Team(
          teamId,
          name,
          AuthTestSupport.persistedUser.createdAt,
          AuthTestSupport.persistedUser.updatedAt
        ).pure[ConnectionIO]
      }
    }

    val userRepo = new UserRepository {
      override val tableName = "users"
      override val columns = Nil

      def create(
          keycloakId: String,
          email: String,
          firstName: String,
          lastName: String,
          teamId: UUID
      ) = AuthTestSupport.persistedUser.pure[ConnectionIO]

      def update(
          id: UUID,
          firstName: String,
          lastName: String,
          teamId: UUID
      ) = AuthTestSupport.persistedUser.pure[ConnectionIO]

      def upsert(input: UpsertUserInput, teamId: UUID) = {
        observedUpsert = Some(input -> teamId)
        AuthTestSupport.persistedUser.copy(teamId = teamId).pure[ConnectionIO]
      }

      def findByKeycloakId(keycloakId: String) =
        AuthTestSupport.persistedUser.pure[ConnectionIO]
    }

    val service = UserService.make(userRepo, teamRepo, pureDatabase)

    for {
      user <- service.syncUser(input)
    } yield expect(observedTeamName.contains("backend")) and
      expect(
        observedUpsert.contains(
          UpsertUserInput(
            keycloakId = input.keycloakId,
            email = input.email,
            firstName = input.firstName,
            lastName = input.lastName,
            teamName = input.team
          ) -> teamId
        )
      ) and
      expect(user.teamId == teamId)
  }

  test("getUserByKeycloakId delegates to the user repository") {
    var requestedKeycloakId: Option[String] = None

    val teamRepo = new TeamRepository {
      override val tableName = "teams"
      override val columns = Nil

      def create(name: String) =
        failConnection("create should not be called")

      def findByName(name: String) =
        failConnection("findByName should not be called")

      def findOrCreate(name: String) =
        failConnection("findOrCreate should not be called")
    }

    val userRepo = new UserRepository {
      override val tableName = "users"
      override val columns = Nil

      def create(
          keycloakId: String,
          email: String,
          firstName: String,
          lastName: String,
          teamId: UUID
      ) = failConnection("create should not be called")

      def update(
          id: UUID,
          firstName: String,
          lastName: String,
          teamId: UUID
      ) = failConnection("update should not be called")

      def upsert(input: UpsertUserInput, teamId: UUID) =
        failConnection("upsert should not be called")

      def findByKeycloakId(keycloakId: String) = {
        requestedKeycloakId = Some(keycloakId)
        AuthTestSupport.persistedUser.pure[ConnectionIO]
      }
    }

    val service = UserService.make(userRepo, teamRepo, pureDatabase)

    for {
      user <- service.getUserByKeycloakId("kc-123")
    } yield expect(requestedKeycloakId.contains("kc-123")) and
      expect(user == AuthTestSupport.persistedUser)
  }

  private val pureDatabase: Database = new Database {
    def transact[A](query: ConnectionIO[A]): IO[A] =
      query.foldMap(unexpectedConnectionOp)
  }

  private val unexpectedConnectionOp = new (ConnectionOp ~> IO) {
    def apply[A](fa: ConnectionOp[A]): IO[A] =
      IO.raiseError(
        new IllegalStateException(s"Unexpected low-level DB operation: $fa")
      )
  }

  private def failConnection[A](message: String): ConnectionIO[A] =
    new IllegalStateException(message).raiseError[ConnectionIO, A]
}
