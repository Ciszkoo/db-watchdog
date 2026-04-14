package dbwatchdog.service

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.{Base64, UUID}

import cats.effect.IO
import cats.implicits.*
import doobie.ConnectionIO

import dbwatchdog.auth.AuthUser
import dbwatchdog.config.AppConfig
import dbwatchdog.database.Database
import dbwatchdog.domain.{
  CreateTemporaryAccessCredentialInput,
  Database as PersistedDatabase,
  DatabaseResponse,
  EffectiveDatabaseAccessResponse,
  IssueOtpResponse,
  User,
  UserDatabaseAccessExtension
}
import dbwatchdog.repository.Repositories

trait AccessService {
  def getEffectiveAccessForUser(
      userId: UUID
  ): IO[List[EffectiveDatabaseAccessResponse]]

  def getEffectiveAccessForAuthenticatedUser(
      authUser: AuthUser
  ): IO[List[EffectiveDatabaseAccessResponse]]

  def issueOtp(
      authUser: AuthUser,
      databaseId: UUID
  ): IO[IssueOtpResponse]
}

object AccessService {
  private val TeamAccessSource = "TEAM"
  private val UserExtensionAccessSource = "USER_EXTENSION"
  private val TeamAndUserExtensionAccessSource = "TEAM_AND_USER_EXTENSION"
  private val secureRandom = SecureRandom()

  def make(
      repos: Repositories,
      userService: UserService,
      db: Database
  )(using config: AppConfig): AccessService =
    new AccessService {
      def getEffectiveAccessForUser(
          userId: UUID
      ): IO[List[EffectiveDatabaseAccessResponse]] =
        db.transact(
          for {
            user <- requireUser(userId)
            access <- resolveEffectiveAccess(user, Instant.now())
          } yield access
        )

      def getEffectiveAccessForAuthenticatedUser(
          authUser: AuthUser
      ): IO[List[EffectiveDatabaseAccessResponse]] =
        for {
          user <- userService.syncUser(authUser.toSyncInput)
          access <- db.transact(resolveEffectiveAccess(user, Instant.now()))
        } yield access

      def issueOtp(
          authUser: AuthUser,
          databaseId: UUID
      ): IO[IssueOtpResponse] =
        for {
          user <- userService.syncUser(authUser.toSyncInput)
          now <- IO.realTimeInstant
          otp <- generateOtp
          otpHash = sha256Hex(otp)
          response <- db.transact(
            for {
              database <- requireDatabase(databaseId)
              accessibleDatabases <- resolveEffectiveAccess(user, now)
              _ <-
                if accessibleDatabases.exists(_.databaseId == databaseId) then
                  ().pure[ConnectionIO]
                else
                  ServiceError
                    .Forbidden(
                      s"User ${user.id} cannot request OTP for database $databaseId"
                    )
                    .raiseError[ConnectionIO, Unit]
              expiresAt = now.plusSeconds(config.otp.ttlSeconds)
              _ <- repos.temporaryAccessCredentials
                .invalidateActiveForUserAndDatabase(user.id, databaseId, now)
              credential <- repos.temporaryAccessCredentials.create(
                CreateTemporaryAccessCredentialInput(
                  userId = user.id,
                  databaseId = databaseId,
                  otpHash = otpHash,
                  expiresAt = expiresAt
                )
              )
            } yield IssueOtpResponse(
              credentialId = credential.id,
              otp = otp,
              expiresAt = expiresAt,
              database = DatabaseResponse.fromDomain(database)
            )
          )
        } yield response

      private def resolveEffectiveAccess(
          user: User,
          now: Instant
      ): ConnectionIO[List[EffectiveDatabaseAccessResponse]] =
        for {
          teamGrantedDatabaseIds <- repos.teamDatabaseGrants
            .findDatabaseIdsByTeamId(
              user.teamId
            )
          queriedExtensions <- repos.userDatabaseAccessExtensions
            .findActiveByUserId(
              user.id,
              now
            )
          activeExtensions = queriedExtensions.filter(extension =>
            extension.expiresAt.forall(_.isAfter(now))
          )
          databases <- repos.databases.findActiveByIds(
            teamGrantedDatabaseIds.toSet ++ activeExtensions.map(_.databaseId)
          )
        } yield {
          val teamGrantIds = teamGrantedDatabaseIds.toSet
          val extensionsByDatabaseId =
            activeExtensions
              .map(extension => extension.databaseId -> extension)
              .toMap

          databases
            .map(database =>
              toEffectiveAccessResponse(
                database = database,
                loginIdentifier = user.email,
                teamGrantIds = teamGrantIds,
                extension = extensionsByDatabaseId.get(database.id)
              )
            )
            .sortBy(response =>
              (
                response.databaseName,
                response.host,
                response.port
              )
            )
        }

      private def toEffectiveAccessResponse(
          database: PersistedDatabase,
          loginIdentifier: String,
          teamGrantIds: Set[UUID],
          extension: Option[UserDatabaseAccessExtension]
      ): EffectiveDatabaseAccessResponse = {
        val accessSource =
          (teamGrantIds.contains(database.id), extension.isDefined) match
            case (true, true)   => TeamAndUserExtensionAccessSource
            case (true, false)  => TeamAccessSource
            case (false, true)  => UserExtensionAccessSource
            case (false, false) => UserExtensionAccessSource

        EffectiveDatabaseAccessResponse(
          databaseId = database.id,
          engine = database.engine,
          host = database.host,
          port = database.port,
          databaseName = database.databaseName,
          loginIdentifier = loginIdentifier,
          accessSource = accessSource,
          extensionExpiresAt = extension.flatMap(_.expiresAt)
        )
      }

      private def requireUser(userId: UUID): ConnectionIO[User] =
        repos.users
          .findById(userId)
          .flatMap(
            _.liftTo[ConnectionIO](
              ServiceError.NotFound(s"User $userId not found")
            )
          )

      private def requireDatabase(
          databaseId: UUID
      ): ConnectionIO[PersistedDatabase] =
        repos.databases
          .findActiveById(databaseId)
          .flatMap(
            _.liftTo[ConnectionIO](
              ServiceError.NotFound(s"Database $databaseId not found")
            )
          )

      private def generateOtp: IO[String] =
        IO.blocking {
          val bytes = Array.ofDim[Byte](config.otp.randomBytes)
          secureRandom.nextBytes(bytes)
          Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
        }
    }

  private def sha256Hex(value: String): String = {
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
    digest.map(byte => f"$byte%02x").mkString
  }
}
