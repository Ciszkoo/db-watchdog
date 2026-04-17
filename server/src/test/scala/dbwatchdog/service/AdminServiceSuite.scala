package dbwatchdog.service

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.implicits.*
import cats.~>
import doobie.ConnectionIO
import doobie.free.connection.ConnectionOp
import weaver.SimpleIOSuite

import dbwatchdog.config.AppConfig
import dbwatchdog.database.Database
import dbwatchdog.domain.{
  AdminTeamDatabaseGrantResponse,
  AdminUserDatabaseAccessExtensionResponse,
  CreateDatabase,
  CreateDatabaseSessionInput,
  Database as PersistedDatabase,
  DatabaseSession,
  ListAdminSessionsQuery,
  Team,
  TeamDatabaseGrant,
  TechnicalCredentialRewrapResponse,
  UpdateDatabase,
  UpdateDatabaseRequest,
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
  private val baseAppConfig = AppConfig(
    server = AppConfig.ServerConfig("127.0.0.1", 8080),
    db = AppConfig.DatabaseConfig(
      host = "localhost",
      port = 5432,
      user = "postgres",
      password = "password",
      schema = "public",
      threadPoolSize = 2
    ),
    keycloak = AppConfig.KeycloakConfig(
      issuer = "https://issuer.example.test/realms/db-watchdog",
      jwksUrl = "https://issuer.example.test/jwks",
      audience = "db-watchdog-backend",
      authorizedParty = "db-watchdog-frontend"
    ),
    otp = AppConfig.OtpConfig(
      ttlSeconds = 300,
      randomBytes = 18
    ),
    credentialEncryption = AppConfig.CredentialEncryptionConfig(
      key = Some("current-technical-credentials-key"),
      previousKey = None
    )
  )
  private given AppConfig = baseAppConfig

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
    updatedAt = updatedAt,
    deactivatedAt = None
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

  private val otherTeamGrant = TeamDatabaseGrant(
    id = UUID.fromString("abababab-abab-abab-abab-abababababab"),
    teamId = UUID.fromString("99999999-9999-9999-9999-999999999999"),
    databaseId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  private val teamGrant = TeamDatabaseGrant(
    id = UUID.fromString("12121212-1212-1212-1212-121212121212"),
    teamId = team.id,
    databaseId = database.id,
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  private val otherExtension = UserDatabaseAccessExtension(
    id = UUID.fromString("13131313-1313-1313-1313-131313131313"),
    userId = UUID.fromString("88888888-8888-8888-8888-888888888888"),
    databaseId = UUID.fromString("66666666-6666-6666-6666-666666666666"),
    expiresAt = None,
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  private val userExtension = UserDatabaseAccessExtension(
    id = UUID.fromString("14141414-1414-1414-1414-141414141414"),
    userId = user.id,
    databaseId = database.id,
    expiresAt = Some(Instant.parse("2024-01-05T10:00:00Z")),
    createdAt = createdAt,
    updatedAt = updatedAt
  )

  test(
    "listSessions preserves repository order and populates nested user and database payloads"
  ) {
    val query = ListAdminSessionsQuery()
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
      sessions <- service.listSessions(query)
    } yield expect(
      sessions.items.map(_.id) == List(newerSession.id, olderSession.id)
    ) and
      expect(sessions.items.headOption.exists(_.user.email == user.email)) and
      expect(
        sessions.items.headOption.exists(_.user.team.name == team.name)
      ) and
      expect(sessions.items.headOption.exists(_.database.id == database.id)) and
      expect(
        sessions.items.headOption.exists(
          _.database.technicalUser == database.technicalUser
        )
      )
  }

  test("listSessions returns the requested paging metadata and total count") {
    var observedUserIds = Option.empty[Set[UUID]]
    var observedTeamIds = Option.empty[Set[UUID]]
    var observedListQuery = Option.empty[ListAdminSessionsQuery]
    var observedCountQuery = Option.empty[ListAdminSessionsQuery]
    val query =
      ListAdminSessionsQuery(page = 2, pageSize = 50, userId = Some(user.id))
    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(
          List(user),
          onFindByIds = ids => observedUserIds = Some(ids)
        ),
        teams = stubTeamRepository(
          List(team),
          onFindByIds = ids => observedTeamIds = Some(ids)
        ),
        databases = stubDatabaseRepository(List(database)),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(
          sessions = List(olderSession),
          totalCount = 42L,
          onListPage = observed => observedListQuery = Some(observed),
          onCount = observed => observedCountQuery = Some(observed)
        )
      ),
      db = pureDatabase
    )
    for {
      response <- service.listSessions(query)
    } yield expect(response.page == 2) and
      expect(response.pageSize == 50) and
      expect(response.totalCount == 42L) and
      expect(response.items.map(_.id) == List(olderSession.id)) and
      expect(observedUserIds.contains(Set(user.id))) and
      expect(observedTeamIds.contains(Set(team.id))) and
      expect(observedListQuery.contains(query)) and
      expect(observedCountQuery.contains(query))
  }

  test("listSessions fails fast when a referenced user is missing") {
    val query = ListAdminSessionsQuery()
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
      result <- service.listSessions(query).attempt
    } yield expect(
      result.left.exists(_.getMessage.contains(s"Missing user ${user.id}"))
    )
  }

  test("listTeamDatabaseGrants returns stable ordered flat records") {
    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(List(database)),
        teamDatabaseGrants =
          stubTeamDatabaseGrantRepository(List(otherTeamGrant, teamGrant)),
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      grants <- service.listTeamDatabaseGrants()
    } yield expect(
      grants == List(
        AdminTeamDatabaseGrantResponse.fromDomain(otherTeamGrant),
        AdminTeamDatabaseGrantResponse.fromDomain(teamGrant)
      )
    )
  }

  test("listUserDatabaseAccessExtensions returns stable ordered records") {
    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(List(database)),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          stubUserDatabaseAccessExtensionRepository(
            List(otherExtension, userExtension)
          ),
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      extensions <- service.listUserDatabaseAccessExtensions()
    } yield expect(
      extensions == List(
        AdminUserDatabaseAccessExtensionResponse.fromDomain(otherExtension),
        AdminUserDatabaseAccessExtensionResponse.fromDomain(userExtension)
      )
    ) and expect(
      extensions.lastOption
        .flatMap(_.expiresAt)
        .contains(userExtension.expiresAt.get)
    )
  }

  test(
    "updateDatabase preserves the stored password when technicalPassword is omitted"
  ) {
    var observedUpdate: Option[UpdateDatabase] = None

    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(
          List(database),
          onUpdate = (_, input) => observedUpdate = Some(input)
        ),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      updated <- service.updateDatabase(
        database.id,
        UpdateDatabaseRequest(
          engine = database.engine,
          host = "edited.internal",
          port = 6432,
          technicalUser = "edited_user",
          technicalPassword = None,
          databaseName = "analytics_reporting"
        )
      )
    } yield expect(
      observedUpdate.exists(_.technicalPassword == database.technicalPassword)
    ) and
      expect(updated.host == "edited.internal") and
      expect(updated.port == 6432) and
      expect(updated.technicalUser == "edited_user") and
      expect(updated.databaseName == "analytics_reporting")
  }

  test(
    "updateDatabase rotates the stored password when technicalPassword is provided"
  ) {
    var observedUpdate: Option[UpdateDatabase] = None

    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(
          List(database),
          onUpdate = (_, input) => observedUpdate = Some(input)
        ),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      _ <- service.updateDatabase(
        database.id,
        UpdateDatabaseRequest(
          engine = database.engine,
          host = database.host,
          port = database.port,
          technicalUser = database.technicalUser,
          technicalPassword = Some("rotated-secret"),
          databaseName = database.databaseName
        )
      )
    } yield expect(
      observedUpdate.exists(_.technicalPassword == "rotated-secret")
    )
  }

  test(
    "deactivateDatabase invalidates active OTPs and returns the inactive row"
  ) {
    var invalidatedDatabaseId: Option[UUID] = None

    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(List(database)),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials =
          stubTemporaryAccessCredentialRepository(onInvalidateForDatabase =
            (databaseId, _) => invalidatedDatabaseId = Some(databaseId)
          ),
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      updated <- service.deactivateDatabase(database.id)
    } yield expect(invalidatedDatabaseId.contains(database.id)) and
      expect(updated.id == database.id) and
      expect(!updated.isActive) and
      expect(updated.deactivatedAt.nonEmpty)
  }

  test(
    "grant and extension mutations reject inactive databases with conflict"
  ) {
    val inactiveDatabase = database.copy(
      deactivatedAt = Some(Instant.parse("2024-01-06T00:00:00Z"))
    )
    var grantUpsertCalled = false
    var extensionUpsertCalled = false

    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(List(inactiveDatabase)),
        teamDatabaseGrants = new TeamDatabaseGrantRepository {
          override val tableName = "team_database_grants"
          override val columns = Nil

          def upsert(input: UpsertTeamDatabaseGrantInput) = {
            grantUpsertCalled = true
            failConnection("upsert should not be called")
          }

          def list = List.empty[TeamDatabaseGrant].pure[ConnectionIO]

          def findDatabaseIdsByTeamId(teamId: UUID) =
            List.empty[UUID].pure[ConnectionIO]

          def delete(teamId: UUID, databaseId: UUID) =
            failConnection("delete should not be called")
        },
        userDatabaseAccessExtensions =
          new UserDatabaseAccessExtensionRepository {
            override val tableName = "user_database_access_extensions"
            override val columns = Nil

            def upsert(input: UpsertUserDatabaseAccessExtensionInput) = {
              extensionUpsertCalled = true
              failConnection("upsert should not be called")
            }

            def list =
              List.empty[UserDatabaseAccessExtension].pure[ConnectionIO]

            def findActiveByUserId(userId: UUID, now: Instant) =
              List.empty[UserDatabaseAccessExtension].pure[ConnectionIO]

            def delete(userId: UUID, databaseId: UUID) =
              failConnection("delete should not be called")
          },
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      grantResult <- service
        .upsertTeamDatabaseGrant(
          dbwatchdog.domain
            .UpsertTeamDatabaseGrantRequest(team.id, inactiveDatabase.id)
        )
        .attempt
      extensionResult <- service
        .upsertUserDatabaseAccessExtension(
          dbwatchdog.domain.UpsertUserDatabaseAccessExtensionRequest(
            user.id,
            inactiveDatabase.id,
            Some(Instant.parse("2099-01-01T00:00:00Z"))
          )
        )
        .attempt
    } yield expect(
      grantResult.left.exists(_.getMessage.contains("inactive"))
    ) and expect(
      extensionResult.left.exists(_.getMessage.contains("inactive"))
    ) and expect(!grantUpsertCalled) and
      expect(!extensionUpsertCalled)
  }

  test("rewrapTechnicalCredentials returns the rotated row count") {
    given AppConfig = baseAppConfig.copy(
      credentialEncryption = baseAppConfig.credentialEncryption.copy(
        previousKey = Some("previous-technical-credentials-key")
      )
    )

    var rewrapCalls = 0

    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(
          List(database),
          onRewrap = () => {
            rewrapCalls = rewrapCalls + 1
            2
          }
        ),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      response <- service.rewrapTechnicalCredentials()
    } yield expect(response == TechnicalCredentialRewrapResponse(2)) and
      expect(rewrapCalls == 1)
  }

  test("rewrapTechnicalCredentials rejects a missing previous key") {
    given AppConfig = baseAppConfig

    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(List(database)),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      result <- service.rewrapTechnicalCredentials().attempt
    } yield expect(
      result.left.exists {
        case ServiceError.Conflict(message) =>
          message.contains("TECHNICAL_CREDENTIALS_PREVIOUS_KEY")
        case _ => false
      }
    )
  }

  test("rewrapTechnicalCredentials rejects when previous key matches current") {
    given AppConfig = baseAppConfig.copy(
      credentialEncryption = baseAppConfig.credentialEncryption.copy(
        previousKey = Some("current-technical-credentials-key")
      )
    )

    val service = AdminService.make(
      repos = Repositories(
        users = stubUserRepository(List(user)),
        teams = stubTeamRepository(List(team)),
        databases = stubDatabaseRepository(List(database)),
        teamDatabaseGrants = noopTeamDatabaseGrantRepository,
        userDatabaseAccessExtensions =
          noopUserDatabaseAccessExtensionRepository,
        temporaryAccessCredentials = noopTemporaryAccessCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository(Nil)
      ),
      db = pureDatabase
    )

    for {
      result <- service.rewrapTechnicalCredentials().attempt
    } yield expect(
      result.left.exists {
        case ServiceError.Conflict(message) =>
          message.contains("TECHNICAL_CREDENTIALS_PREVIOUS_KEY")
        case _ => false
      }
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

  private def stubUserRepository(
      users: List[User],
      onFindByIds: Set[UUID] => Unit = _ => ()
  ): UserRepository =
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

      override def findByIds(ids: Set[UUID]) = {
        onFindByIds(ids)
        users.filter(user => ids.contains(user.id)).pure[ConnectionIO]
      }

      def findByKeycloakId(keycloakId: String) =
        users
          .find(_.keycloakId == keycloakId)
          .liftTo[ConnectionIO](
            new IllegalStateException(s"Missing keycloakId $keycloakId")
          )
    }

  private def stubTeamRepository(
      teams: List[Team],
      onFindByIds: Set[UUID] => Unit = _ => ()
  ): TeamRepository =
    new TeamRepository {
      override val tableName = "teams"
      override val columns = Nil

      def create(name: String) = failConnection("create should not be called")

      def list = teams.pure[ConnectionIO]

      def findById(id: UUID) = teams.find(_.id == id).pure[ConnectionIO]

      override def findByIds(ids: Set[UUID]) = {
        onFindByIds(ids)
        teams.filter(team => ids.contains(team.id)).pure[ConnectionIO]
      }

      def findByName(name: String) =
        teams.find(_.name == name).pure[ConnectionIO]

      def findOrCreate(name: String) =
        failConnection("findOrCreate should not be called")
    }

  private def stubDatabaseRepository(
      databases: List[PersistedDatabase],
      onUpdate: (UUID, UpdateDatabase) => Unit = (_, _) => (),
      onRewrap: () => Int = () => 0
  ): DatabaseRepository =
    new DatabaseRepository {
      override val tableName = "databases"
      override val columns = Nil

      def insert(input: CreateDatabase) =
        failConnection("insert should not be called")

      def update(id: UUID, input: UpdateDatabase) =
        databases
          .find(_.id == id)
          .map { existing =>
            onUpdate(id, input)
            existing.copy(
              engine = input.engine,
              host = input.host,
              port = input.port,
              technicalUser = input.technicalUser,
              technicalPassword = input.technicalPassword,
              databaseName = input.databaseName,
              updatedAt = Instant.parse("2024-01-03T00:00:00Z")
            )
          }
          .liftTo[ConnectionIO](
            new IllegalStateException(s"Missing database $id")
          )

      def deactivate(id: UUID, now: Instant) =
        databases
          .find(_.id == id)
          .map(existing =>
            existing.copy(
              updatedAt =
                if existing.deactivatedAt.isEmpty then now
                else existing.updatedAt,
              deactivatedAt = existing.deactivatedAt.orElse(Some(now))
            )
          )
          .liftTo[ConnectionIO](
            new IllegalStateException(s"Missing database $id")
          )

      def reactivate(id: UUID) =
        databases
          .find(_.id == id)
          .map(existing =>
            existing.copy(
              updatedAt =
                if existing.deactivatedAt.nonEmpty then
                  Instant.parse("2024-01-05T00:00:00Z")
                else existing.updatedAt,
              deactivatedAt = None
            )
          )
          .liftTo[ConnectionIO](
            new IllegalStateException(s"Missing database $id")
          )

      def rewrapTechnicalCredentials() =
        onRewrap().pure[ConnectionIO]

      def list = databases.pure[ConnectionIO]

      def findById(id: UUID) = databases.find(_.id == id).pure[ConnectionIO]

      def findByIds(ids: Set[UUID]) =
        databases
          .filter(database => ids.contains(database.id))
          .pure[ConnectionIO]

      def findActiveById(id: UUID) =
        databases
          .find(database => database.id == id && database.deactivatedAt.isEmpty)
          .pure[ConnectionIO]

      def findActiveByIds(ids: Set[UUID]) =
        databases
          .filter(database =>
            ids.contains(database.id) && database.deactivatedAt.isEmpty
          )
          .pure[ConnectionIO]
    }

  private def stubDatabaseSessionRepository(
      sessions: List[DatabaseSession],
      totalCount: Long = 0L,
      onListPage: ListAdminSessionsQuery => Unit = _ => (),
      onCount: ListAdminSessionsQuery => Unit = _ => ()
  ): DatabaseSessionRepository =
    new DatabaseSessionRepository {
      override val tableName = "database_sessions"
      override val columns = Nil

      def create(input: CreateDatabaseSessionInput) =
        failConnection("create should not be called")

      def listPage(query: ListAdminSessionsQuery) = {
        onListPage(query)
        sessions
      }.pure[ConnectionIO]

      def count(query: ListAdminSessionsQuery) = {
        onCount(query)
        totalCount
      }.pure[ConnectionIO]

      def markEnded(
          id: UUID,
          endedAt: Instant,
          bytesSent: Long,
          bytesReceived: Long
      ) = failConnection("markEnded should not be called")
    }

  private def stubTeamDatabaseGrantRepository(
      grants: List[TeamDatabaseGrant]
  ): TeamDatabaseGrantRepository =
    new TeamDatabaseGrantRepository {
      override val tableName = "team_database_grants"
      override val columns = Nil

      def upsert(input: UpsertTeamDatabaseGrantInput) =
        failConnection("upsert should not be called")

      def list = grants.pure[ConnectionIO]

      def findDatabaseIdsByTeamId(teamId: UUID) =
        List.empty[UUID].pure[ConnectionIO]

      def delete(teamId: UUID, databaseId: UUID) =
        failConnection("delete should not be called")
    }

  private val noopTeamDatabaseGrantRepository: TeamDatabaseGrantRepository =
    stubTeamDatabaseGrantRepository(Nil)

  private def stubUserDatabaseAccessExtensionRepository(
      extensions: List[UserDatabaseAccessExtension]
  ): UserDatabaseAccessExtensionRepository =
    new UserDatabaseAccessExtensionRepository {
      override val tableName = "user_database_access_extensions"
      override val columns = Nil

      def upsert(input: UpsertUserDatabaseAccessExtensionInput) =
        failConnection("upsert should not be called")

      def list = extensions.pure[ConnectionIO]

      def findActiveByUserId(userId: UUID, now: Instant) =
        List.empty[UserDatabaseAccessExtension].pure[ConnectionIO]

      def delete(userId: UUID, databaseId: UUID) =
        failConnection("delete should not be called")
    }

  private val noopUserDatabaseAccessExtensionRepository
      : UserDatabaseAccessExtensionRepository =
    stubUserDatabaseAccessExtensionRepository(Nil)

  private val noopTemporaryAccessCredentialRepository
      : TemporaryAccessCredentialRepository =
    stubTemporaryAccessCredentialRepository()

  private def stubTemporaryAccessCredentialRepository(
      onInvalidateForDatabase: (UUID, Instant) => Unit = (_, _) => ()
  ): TemporaryAccessCredentialRepository =
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

      def invalidateActiveForDatabase(
          databaseId: UUID,
          now: Instant
      ) = {
        onInvalidateForDatabase(databaseId, now)
        1.pure[ConnectionIO]
      }
    }
}
