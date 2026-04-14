package dbwatchdog.service

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.implicits.*
import cats.~>
import doobie.ConnectionIO
import doobie.free.connection.ConnectionOp
import weaver.SimpleIOSuite

import dbwatchdog.database.Database
import dbwatchdog.domain.{
  CreateDatabase,
  CreateDatabaseSessionInput,
  Database as PersistedDatabase,
  DatabaseSession,
  Team,
  UpsertTeamDatabaseGrantInput,
  UpsertUserDatabaseAccessExtensionInput,
  UpsertUserInput,
  User,
  UserDatabaseAccessExtension
}
import dbwatchdog.repository.*

object AdminServiceSuite extends SimpleIOSuite {
  private val createdAt = Instant.parse("2024-01-01T00:00:00Z")
  private val updatedAt = Instant.parse("2024-01-02T00:00:00Z")

  private val team = Team(
    id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
    name = "backend",
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  private val user = User(
    id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
    keycloakId = "kc-123",
    email = "john@example.com",
    firstName = "John",
    lastName = "Doe",
    teamId = team.id,
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  private val database = PersistedDatabase(
    id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
    engine = "postgres",
    host = "db.internal",
    port = 5432,
    technicalUser = "technical_user",
    technicalPassword = "super-secret",
    databaseName = "analytics",
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  private val newerSession = DatabaseSession(
    id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
    userId = user.id,
    databaseId = database.id,
    credentialId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
    clientAddr = "127.0.0.1:5432",
    startedAt = Instant.parse("2024-01-03T10:00:00Z"),
    endedAt = Some(Instant.parse("2024-01-03T10:05:00Z")),
    bytesSent = Some(120L),
    bytesReceived = Some(240L),
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  private val olderSession = DatabaseSession(
    id = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
    userId = user.id,
    databaseId = database.id,
    credentialId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
    clientAddr = "127.0.0.1:5433",
    startedAt = Instant.parse("2024-01-02T10:00:00Z"),
    endedAt = None,
    bytesSent = None,
    bytesReceived = None,
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  test(
    "listSessions preserves repository order and populates nested user and database payloads"
  ) {
    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(List(database)),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions =
          stubDatabaseSessionRepository(List(newerSession, olderSession))
      ),
      db = pureDatabase
    )

    for {
      sessions <- service.listSessions()
    } yield expect(
      sessions.map(_.id) == List(newerSession.id, olderSession.id)
    ) and
      expect(sessions.headOption.exists(_.user.email == user.email)) and
      expect(sessions.headOption.exists(_.user.team.name == team.name)) and
      expect(sessions.headOption.exists(_.database.id == database.id)) and
      expect(
        sessions.headOption.exists(
          _.database.technicalUser == database.technicalUser
        )
      )
  }

  test("listSessions fails fast when a referenced user is missing") {
    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(Nil),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(List(database)),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(List(newerSession))
      ),
      db = pureDatabase
    )

    for {
      result <- service.listSessions().attempt
    } yield expect(
      result.left.exists(_.getMessage.contains(s"Missing user ${user.id}"))
    )
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

  private def stubUserRepository(users: List[User]): UserRepository =
    new UserRepository {
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

      def list = users.pure[ConnectionIO]

      def findById(id: UUID) = users.find(_.id == id).pure[ConnectionIO]

      def findByKeycloakId(keycloakId: String) =
        users
          .find(_.keycloakId == keycloakId)
          .liftTo[ConnectionIO](
            new IllegalStateException(s"Missing keycloakId $keycloakId")
          )
    }

  private def stubTeamRepository(teams: List[Team]): TeamRepository =
    new TeamRepository {
      override val tableName = "teams"
      override val columns = Nil

      def create(name: String) = failConnection("create should not be called")

      def list = teams.pure[ConnectionIO]

      def findById(id: UUID) = teams.find(_.id == id).pure[ConnectionIO]

      def findByName(name: String) =
        teams.find(_.name == name).pure[ConnectionIO]

      def findOrCreate(name: String) =
        failConnection("findOrCreate should not be called")
    }

  private def stubDatabaseRepository(
      databases: List[PersistedDatabase]
  ): DatabaseRepository =
    new DatabaseRepository {
      override val tableName = "databases"
      override val columns = Nil

      def insert(input: CreateDatabase) =
        failConnection("insert should not be called")

      def list = databases.pure[ConnectionIO]

      def findById(id: UUID) = databases.find(_.id == id).pure[ConnectionIO]

      def findByIds(ids: Set[UUID]) =
        databases
          .filter(database => ids.contains(database.id))
          .pure[ConnectionIO]
    }

  private def stubDatabaseSessionRepository(
      sessions: List[DatabaseSession]
  ): DatabaseSessionRepository =
    new DatabaseSessionRepository {
      override val tableName = "database_sessions"
      override val columns = Nil

      def create(input: CreateDatabaseSessionInput) =
        failConnection("create should not be called")

      def list = sessions.pure[ConnectionIO]

      def markEnded(
          id: UUID,
          endedAt: Instant,
          bytesSent: Long,
          bytesReceived: Long
      ) = failConnection("markEnded should not be called")
    }

  private val noopTeamDatabaseGrantRepository: TeamDatabaseGrantRepository =
    new TeamDatabaseGrantRepository {
      override val tableName = "team_database_grants"
      override val columns = Nil

      def upsert(input: UpsertTeamDatabaseGrantInput) =
        failConnection("upsert should not be called")

      def findDatabaseIdsByTeamId(teamId: UUID) =
        List.empty[UUID].pure[ConnectionIO]

      def delete(teamId: UUID, databaseId: UUID) =
        failConnection("delete should not be called")
    }

  private val noopUserDatabaseAccessExtensionRepository
      : UserDatabaseAccessExtensionRepository =
    new UserDatabaseAccessExtensionRepository {
      override val tableName = "user_database_access_extensions"
      override val columns = Nil

      def upsert(input: UpsertUserDatabaseAccessExtensionInput) =
        failConnection("upsert should not be called")

      def findActiveByUserId(userId: UUID, now: Instant) =
        List.empty[UserDatabaseAccessExtension].pure[ConnectionIO]

      def delete(userId: UUID, databaseId: UUID) =
        failConnection("delete should not be called")
    }

  private val noopTemporaryAccessCredentialRepository
      : TemporaryAccessCredentialRepository =
    new TemporaryAccessCredentialRepository {
      override val tableName = "temporary_access_credentials"
      override val columns = Nil

      def create(
          input: dbwatchdog.domain.CreateTemporaryAccessCredentialInput
      ) =
        failConnection("create should not be called")

      def markUsed(id: UUID, usedAt: Instant) =
        failConnection("markUsed should not be called")

      def invalidateActiveForUserAndDatabase(
          userId: UUID,
          databaseId: UUID,
          now: Instant
      ) = failConnection(
        "invalidateActiveForUserAndDatabase should not be called"
      )
    }
}
