package dbwatchdog.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.implicits.*
import cats.~>
import doobie.ConnectionIO
import doobie.free.connection.ConnectionOp
import io.circe.syntax.*
import weaver.SimpleIOSuite

import dbwatchdog.config.AppConfig
import dbwatchdog.database.Database
import dbwatchdog.domain.{
  AuthenticatedUserSyncInput,
  CreateDatabase,
  CreateTemporaryAccessCredentialInput,
  Database as PersistedDatabase,
  Team,
  TeamDatabaseGrant,
  TemporaryAccessCredential,
  UpsertTeamDatabaseGrantInput,
  UpsertUserDatabaseAccessExtensionInput,
  UpsertUserInput,
  User,
  UserDatabaseAccessExtension
}
import dbwatchdog.repository.*
import dbwatchdog.support.AuthTestSupport

object AccessServiceSuite extends SimpleIOSuite {
  private given AppConfig = AppConfig(
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
      issuer = AuthTestSupport.issuer,
      jwksUrl = "https://issuer.example.test/jwks",
      audience = AuthTestSupport.audience,
      authorizedParty = AuthTestSupport.authorizedParty
    ),
    otp = AppConfig.OtpConfig(
      ttlSeconds = 300,
      randomBytes = 18
    ),
    credentialEncryption = AppConfig.CredentialEncryptionConfig(
      key = Some("test-technical-credentials-key"),
      sessionSetting = "app.technical_credentials_key"
    )
  )

  private val user = AuthTestSupport.persistedUser
  private val team = Team(
    id = user.teamId,
    name = "backend",
    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-01T00:00:00Z")
  )
  private val databaseA = PersistedDatabase(
    id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
    engine = "postgres",
    host = "db-a.internal",
    port = 5432,
    technicalUser = "technical_a",
    technicalPassword = "secret-a",
    databaseName = "alpha",
    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
    deactivatedAt = None
  )
  private val databaseB = PersistedDatabase(
    id = UUID.fromString("ffffffff-1111-2222-3333-444444444444"),
    engine = "postgres",
    host = "db-b.internal",
    port = 5433,
    technicalUser = "technical_b",
    technicalPassword = "secret-b",
    databaseName = "beta",
    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
    deactivatedAt = None
  )
  private val inactiveDatabaseA = databaseA.copy(
    deactivatedAt = Some(Instant.parse("2024-01-02T00:00:00Z"))
  )
  private val futureExpiry = Instant.parse("2099-01-01T00:00:00Z")
  private val pastExpiry = Instant.parse("2020-01-01T00:00:00Z")

  test("effective access resolves team-only grants") {
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository =
        stubTeamDatabaseGrantRepository(List(databaseA.id)),
      extensionRepository = stubExtensionRepository(Nil),
      databaseRepository = stubDatabaseRepository(List(databaseA))
    )

    for {
      access <- service.getEffectiveAccessForUser(user.id)
    } yield expect(access.map(_.databaseId) == List(databaseA.id)) and
      expect(access.headOption.exists(_.accessSource == "TEAM")) and
      expect(access.headOption.exists(_.loginIdentifier == user.email))
  }

  test("effective access resolves extension-only grants") {
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository = stubTeamDatabaseGrantRepository(Nil),
      extensionRepository = stubExtensionRepository(
        List(extension(databaseB.id, Some(futureExpiry)))
      ),
      databaseRepository = stubDatabaseRepository(List(databaseB))
    )

    for {
      access <- service.getEffectiveAccessForUser(user.id)
    } yield expect(access.map(_.databaseId) == List(databaseB.id)) and
      expect(access.headOption.exists(_.accessSource == "USER_EXTENSION")) and
      expect(
        access.headOption.exists(_.extensionExpiresAt.contains(futureExpiry))
      )
  }

  test(
    "effective access merges team and extension sources for the same database"
  ) {
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository =
        stubTeamDatabaseGrantRepository(List(databaseA.id)),
      extensionRepository = stubExtensionRepository(
        List(extension(databaseA.id, Some(futureExpiry)))
      ),
      databaseRepository = stubDatabaseRepository(List(databaseA))
    )

    for {
      access <- service.getEffectiveAccessForUser(user.id)
    } yield expect(access.size == 1) and
      expect(
        access.headOption.exists(_.accessSource == "TEAM_AND_USER_EXTENSION")
      ) and
      expect(
        access.headOption.exists(_.extensionExpiresAt.contains(futureExpiry))
      )
  }

  test("effective access collapses duplicates by databaseId") {
    var observedIds: Option[Set[UUID]] = None
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository =
        stubTeamDatabaseGrantRepository(List(databaseA.id)),
      extensionRepository = stubExtensionRepository(
        List(extension(databaseA.id, Some(futureExpiry)))
      ),
      databaseRepository = stubDatabaseRepository(
        databases = List(databaseA),
        onFindActiveByIds = ids => observedIds = Some(ids)
      )
    )

    for {
      access <- service.getEffectiveAccessForUser(user.id)
    } yield expect(access.size == 1) and
      expect(observedIds.contains(Set(databaseA.id)))
  }

  test("effective access ignores expired extensions") {
    var observedIds: Option[Set[UUID]] = None
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository = stubTeamDatabaseGrantRepository(Nil),
      extensionRepository = stubExtensionRepository(
        List(extension(databaseA.id, Some(pastExpiry)))
      ),
      databaseRepository = stubDatabaseRepository(
        databases = List(databaseA),
        onFindActiveByIds = ids => observedIds = Some(ids)
      )
    )

    for {
      access <- service.getEffectiveAccessForUser(user.id)
    } yield expect(access.isEmpty) and
      expect(observedIds.contains(Set.empty))
  }

  test("effective access excludes inactive databases") {
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository =
        stubTeamDatabaseGrantRepository(List(inactiveDatabaseA.id)),
      extensionRepository = stubExtensionRepository(
        List(extension(inactiveDatabaseA.id, Some(futureExpiry)))
      ),
      databaseRepository = stubDatabaseRepository(List(inactiveDatabaseA))
    )

    for {
      access <- service.getEffectiveAccessForUser(user.id)
    } yield expect(access.isEmpty)
  }

  test(
    "OTP issuance returns plaintext once, persists a SHA-256 hash, and invalidates older active OTPs"
  ) {
    var invalidated: Option[(UUID, UUID)] = None
    var observedCredentialInput: Option[CreateTemporaryAccessCredentialInput] =
      None
    var eventOrder = Vector.empty[String]
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository =
        stubTeamDatabaseGrantRepository(List(databaseA.id)),
      extensionRepository = stubExtensionRepository(Nil),
      databaseRepository = stubDatabaseRepository(List(databaseA)),
      temporaryCredentialRepository = stubTemporaryCredentialRepository(
        onInvalidate = (userId, databaseId, _) => {
          invalidated = Some(userId -> databaseId)
          eventOrder = eventOrder :+ "invalidate"
        },
        onCreate = input => {
          observedCredentialInput = Some(input)
          eventOrder = eventOrder :+ "create"
        }
      ),
      userService = stubUserService(user)
    )

    for {
      issued <- service.issueOtp(AuthTestSupport.authUser, databaseA.id)
      issuedJson = issued.asJson.noSpaces
    } yield expect(issued.database.id == databaseA.id) and
      expect(issued.otp.nonEmpty) and
      expect(
        observedCredentialInput.exists(input =>
          input.otpHash == sha256Hex(issued.otp)
        )
      ) and
      expect(invalidated.contains(user.id -> databaseA.id)) and
      expect(eventOrder == Vector("invalidate", "create")) and
      expect(issuedJson.contains("otp")) and
      expect(!issuedJson.contains("otpHash"))
  }

  test("OTP issuance rejects users without access to the requested database") {
    var created = false
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository = stubTeamDatabaseGrantRepository(Nil),
      extensionRepository = stubExtensionRepository(Nil),
      databaseRepository = stubDatabaseRepository(List(databaseA)),
      temporaryCredentialRepository = stubTemporaryCredentialRepository(
        onCreate = _ => created = true
      ),
      userService = stubUserService(user)
    )

    for {
      result <- service.issueOtp(AuthTestSupport.authUser, databaseA.id).attempt
    } yield expect(
      result.left.exists {
        case ServiceError.Forbidden(_) => true
        case _                         => false
      }
    ) and expect(!created)
  }

  test("OTP issuance treats inactive databases as unavailable") {
    var created = false
    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository =
        stubTeamDatabaseGrantRepository(List(inactiveDatabaseA.id)),
      extensionRepository = stubExtensionRepository(Nil),
      databaseRepository = stubDatabaseRepository(List(inactiveDatabaseA)),
      temporaryCredentialRepository = stubTemporaryCredentialRepository(
        onCreate = _ => created = true
      ),
      userService = stubUserService(user)
    )

    for {
      result <- service
        .issueOtp(AuthTestSupport.authUser, inactiveDatabaseA.id)
        .attempt
    } yield expect(
      result.left.exists {
        case ServiceError.NotFound(_) => true
        case _                        => false
      }
    ) and expect(!created)
  }

  test(
    "authenticated effective-access syncs the caller from token claims before resolving access"
  ) {
    var observedSyncInputs = Vector.empty[AuthenticatedUserSyncInput]
    var eventOrder = Vector.empty[String]

    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository =
        stubTeamDatabaseGrantRepository(List(databaseA.id)),
      extensionRepository = stubExtensionRepository(Nil),
      databaseRepository = stubDatabaseRepository(
        List(databaseA),
        onFindActiveByIds = _ => eventOrder = eventOrder :+ "resolve-access"
      ),
      userService = recordingUserService(
        resolvedUser = user,
        onSync = input => {
          observedSyncInputs = observedSyncInputs :+ input
          eventOrder = eventOrder :+ "sync"
        }
      )
    )

    for {
      access <- service.getEffectiveAccessForAuthenticatedUser(
        AuthTestSupport.authUser
      )
    } yield expect(access.nonEmpty) and
      expect(
        observedSyncInputs == Vector(AuthTestSupport.authUser.toSyncInput)
      ) and
      expect(eventOrder == Vector("sync", "resolve-access"))
  }

  test(
    "OTP issuance syncs the caller from token claims before access checks and credential writes"
  ) {
    var observedSyncInputs = Vector.empty[AuthenticatedUserSyncInput]
    var eventOrder = Vector.empty[String]

    val service = makeAccessService(
      userRepository = stubUserRepository(foundUser = Some(user)),
      teamDatabaseGrantRepository =
        stubTeamDatabaseGrantRepository(List(databaseA.id)),
      extensionRepository = stubExtensionRepository(Nil),
      databaseRepository = stubDatabaseRepository(
        List(databaseA),
        onFindActiveById = _ => eventOrder = eventOrder :+ "require-database",
        onFindActiveByIds = _ => eventOrder = eventOrder :+ "resolve-access"
      ),
      temporaryCredentialRepository = stubTemporaryCredentialRepository(
        onInvalidate = (_, _, _) => eventOrder = eventOrder :+ "invalidate",
        onCreate = _ => eventOrder = eventOrder :+ "create"
      ),
      userService = recordingUserService(
        resolvedUser = user,
        onSync = input => {
          observedSyncInputs = observedSyncInputs :+ input
          eventOrder = eventOrder :+ "sync"
        }
      )
    )

    for {
      issued <- service.issueOtp(AuthTestSupport.authUser, databaseA.id)
    } yield expect(issued.database.id == databaseA.id) and
      expect(
        observedSyncInputs == Vector(AuthTestSupport.authUser.toSyncInput)
      ) and
      expect(
        eventOrder == Vector(
          "sync",
          "require-database",
          "resolve-access",
          "invalidate",
          "create"
        )
      )
  }

  private def makeAccessService(
      userRepository: UserRepository,
      teamDatabaseGrantRepository: TeamDatabaseGrantRepository,
      extensionRepository: UserDatabaseAccessExtensionRepository,
      databaseRepository: DatabaseRepository,
      temporaryCredentialRepository: TemporaryAccessCredentialRepository =
        stubTemporaryCredentialRepository(),
      userService: UserService = stubUserService(user)
  ): AccessService =
    AccessService.make(
      Repositories(
        users = userRepository,
        teams = stubTeamRepository(),
        databases = databaseRepository,
        teamDatabaseGrants = teamDatabaseGrantRepository,
        userDatabaseAccessExtensions = extensionRepository,
        temporaryAccessCredentials = temporaryCredentialRepository,
        databaseSessions = stubDatabaseSessionRepository()
      ),
      userService,
      pureDatabase
    )

  private def stubUserService(resolvedUser: User): UserService =
    recordingUserService(resolvedUser, _ => ())

  private def recordingUserService(
      resolvedUser: User,
      onSync: AuthenticatedUserSyncInput => Unit
  ): UserService = new UserService {
    def syncUser(input: AuthenticatedUserSyncInput) = {
      onSync(input)
      IO.pure(resolvedUser)
    }

    def getUserByKeycloakId(keycloackId: String) = IO.pure(resolvedUser)
  }

  private def stubTeamRepository(): TeamRepository = new TeamRepository {
    override val tableName = "teams"
    override val columns = Nil

    def create(name: String) = failConnection("create should not be called")
    def list = List(team).pure[ConnectionIO]
    def findById(id: UUID) = Option(team).pure[ConnectionIO]
    def findByName(name: String) = Option(team).pure[ConnectionIO]
    def findOrCreate(name: String) = team.pure[ConnectionIO]
  }

  private def stubUserRepository(
      foundUser: Option[User]
  ): UserRepository = new UserRepository {
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

    def list = List.empty[User].pure[ConnectionIO]

    def findById(id: UUID) =
      foundUser.filter(_.id == id).pure[ConnectionIO]

    def findByKeycloakId(keycloakId: String) =
      foundUser
        .filter(_.keycloakId == keycloakId)
        .liftTo[ConnectionIO](new IllegalStateException("user not found"))
  }

  private def stubDatabaseRepository(
      databases: List[PersistedDatabase],
      onFindActiveById: UUID => Unit = _ => (),
      onFindActiveByIds: Set[UUID] => Unit = _ => ()
  ): DatabaseRepository = new DatabaseRepository {
    override val tableName = "databases"
    override val columns = Nil

    private val databaseIndex =
      databases.map(database => database.id -> database).toMap

    def insert(input: CreateDatabase) =
      failConnection("insert should not be called")

    def update(id: UUID, input: dbwatchdog.domain.UpdateDatabase) =
      failConnection("update should not be called")

    def deactivate(id: UUID, now: Instant) =
      failConnection("deactivate should not be called")

    def reactivate(id: UUID) =
      failConnection("reactivate should not be called")

    def list = databaseIndex.values.toList.pure[ConnectionIO]

    def findById(id: UUID) = databaseIndex.get(id).pure[ConnectionIO]

    def findByIds(ids: Set[UUID]) =
      databaseIndex.values
        .filter(database => ids.contains(database.id))
        .toList
        .pure[ConnectionIO]

    def findActiveById(id: UUID) = {
      onFindActiveById(id)
      databaseIndex
        .get(id)
        .filter(_.deactivatedAt.isEmpty)
        .pure[ConnectionIO]
    }

    def findActiveByIds(ids: Set[UUID]) = {
      onFindActiveByIds(ids)
      databaseIndex.values
        .filter(database =>
          ids.contains(database.id) && database.deactivatedAt.isEmpty
        )
        .toList
        .pure[ConnectionIO]
    }
  }

  private def stubTeamDatabaseGrantRepository(
      databaseIds: List[UUID]
  ): TeamDatabaseGrantRepository = new TeamDatabaseGrantRepository {
    override val tableName = "team_database_grants"
    override val columns = Nil

    def upsert(input: UpsertTeamDatabaseGrantInput) =
      failConnection("upsert should not be called")

    def list = List.empty[TeamDatabaseGrant].pure[ConnectionIO]

    def delete(teamId: UUID, databaseId: UUID) =
      failConnection("delete should not be called")

    def findDatabaseIdsByTeamId(teamId: UUID) =
      databaseIds.pure[ConnectionIO]
  }

  private def stubExtensionRepository(
      extensions: List[UserDatabaseAccessExtension]
  ): UserDatabaseAccessExtensionRepository =
    new UserDatabaseAccessExtensionRepository {
      override val tableName = "user_database_access_extensions"
      override val columns = Nil

      def upsert(input: UpsertUserDatabaseAccessExtensionInput) =
        failConnection("upsert should not be called")

      def list = extensions.pure[ConnectionIO]

      def delete(userId: UUID, databaseId: UUID) =
        failConnection("delete should not be called")

      def findActiveByUserId(userId: UUID, now: Instant) =
        extensions.pure[ConnectionIO]
    }

  private def stubTemporaryCredentialRepository(
      onInvalidate: (UUID, UUID, Instant) => Unit = (_, _, _) => (),
      onCreate: CreateTemporaryAccessCredentialInput => Unit = _ => ()
  ): TemporaryAccessCredentialRepository =
    new TemporaryAccessCredentialRepository {
      override val tableName = "temporary_access_credentials"
      override val columns = Nil

      def create(input: CreateTemporaryAccessCredentialInput) = {
        onCreate(input)
        TemporaryAccessCredential(
          id = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
          userId = input.userId,
          databaseId = input.databaseId,
          otpHash = input.otpHash,
          expiresAt = input.expiresAt,
          usedAt = None,
          createdAt = Instant.parse("2026-01-01T00:00:00Z"),
          updatedAt = Instant.parse("2026-01-01T00:00:00Z")
        ).pure[ConnectionIO]
      }

      def markUsed(id: UUID, usedAt: Instant) =
        failConnection("markUsed should not be called")

      def invalidateActiveForUserAndDatabase(
          userId: UUID,
          databaseId: UUID,
          now: Instant
      ) = {
        onInvalidate(userId, databaseId, now)
        1.pure[ConnectionIO]
      }

      def invalidateActiveForDatabase(
          databaseId: UUID,
          now: Instant
      ) = failConnection("invalidateActiveForDatabase should not be called")
    }

  private def stubDatabaseSessionRepository(): DatabaseSessionRepository =
    new DatabaseSessionRepository {
      override val tableName = "database_sessions"
      override val columns = Nil

      def create(input: dbwatchdog.domain.CreateDatabaseSessionInput) =
        failConnection("create should not be called")

      def list =
        failConnection("list should not be called")

      def markEnded(
          id: UUID,
          endedAt: Instant,
          bytesSent: Long,
          bytesReceived: Long
      ) = failConnection("markEnded should not be called")
    }

  private def extension(
      databaseId: UUID,
      expiresAt: Option[Instant]
  ): UserDatabaseAccessExtension =
    UserDatabaseAccessExtension(
      id = UUID.randomUUID(),
      userId = user.id,
      databaseId = databaseId,
      expiresAt = expiresAt,
      createdAt = Instant.parse("2024-01-01T00:00:00Z"),
      updatedAt = Instant.parse("2024-01-01T00:00:00Z")
    )

  private val pureDatabase: Database = new Database {
    def transact[A](query: ConnectionIO[A]): IO[A] =
      query.foldMap(unexpectedConnectionOp)
  }

  private val unexpectedConnectionOp = new (ConnectionOp ~> IO) {
    def apply[A](fa: ConnectionOp[A]): IO[A] =
      fa match
        case ConnectionOp.RaiseError(error) =>
          IO.raiseError(error)
        case _ =>
          IO.raiseError(
            new IllegalStateException(s"Unexpected low-level DB operation: $fa")
          )
  }

  private def failConnection[A](message: String): ConnectionIO[A] =
    new IllegalStateException(message).raiseError[ConnectionIO, A]

  private def sha256Hex(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"$byte%02x")
      .mkString
}
