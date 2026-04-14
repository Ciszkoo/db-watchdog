package dbwatchdog.routes

import cats.effect.IO
import org.http4s.Method
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.http4s.{Request, Status}
import weaver.SimpleIOSuite

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.{
  AdminDatabaseSessionResponse,
  AdminTeamDatabaseGrantResponse,
  AdminUserDatabaseAccessExtensionResponse,
  AdminUserResponse,
  AuthenticatedUserSyncInput,
  CreateDatabaseRequest,
  DatabaseResponse,
  EffectiveDatabaseAccessResponse,
  TeamResponse,
  UpdateDatabaseRequest,
  UpsertTeamDatabaseGrantRequest,
  UpsertUserDatabaseAccessExtensionRequest
}
import dbwatchdog.service.{AccessService, AdminService, UserService}
import dbwatchdog.support.AuthTestSupport

object RoutesSuite extends SimpleIOSuite {
  private val stubUserService: UserService = new UserService {
    def syncUser(input: AuthenticatedUserSyncInput) =
      IO.pure(AuthTestSupport.persistedUser)

    def getUserByKeycloakId(keycloackId: String) =
      IO.pure(AuthTestSupport.persistedUser)
  }

  private val stubAdminService: AdminService = new AdminService {
    def listTeams() = IO.pure(List.empty[TeamResponse])
    def listUsers() = IO.pure(List.empty[AdminUserResponse])
    def listSessions() = IO.pure(List.empty[AdminDatabaseSessionResponse])
    def listDatabases() = IO.pure(List.empty[DatabaseResponse])
    def listTeamDatabaseGrants() =
      IO.pure(List.empty[AdminTeamDatabaseGrantResponse])
    def listUserDatabaseAccessExtensions() =
      IO.pure(List.empty[AdminUserDatabaseAccessExtensionResponse])
    def createDatabase(request: CreateDatabaseRequest) =
      IO.raiseError(new IllegalStateException("not used"))
    def updateDatabase(
        databaseId: java.util.UUID,
        request: UpdateDatabaseRequest
    ) =
      IO.raiseError(new IllegalStateException("not used"))
    def deactivateDatabase(databaseId: java.util.UUID) =
      IO.raiseError(new IllegalStateException("not used"))
    def reactivateDatabase(databaseId: java.util.UUID) =
      IO.raiseError(new IllegalStateException("not used"))
    def upsertTeamDatabaseGrant(request: UpsertTeamDatabaseGrantRequest) =
      IO.unit
    def deleteTeamDatabaseGrant(
        teamId: java.util.UUID,
        databaseId: java.util.UUID
    ) =
      IO.unit
    def upsertUserDatabaseAccessExtension(
        request: UpsertUserDatabaseAccessExtensionRequest
    ) = IO.unit
    def deleteUserDatabaseAccessExtension(
        userId: java.util.UUID,
        databaseId: java.util.UUID
    ) = IO.unit
  }

  private val stubAccessService: AccessService = new AccessService {
    def getEffectiveAccessForUser(userId: java.util.UUID) =
      IO.pure(List.empty[EffectiveDatabaseAccessResponse])
    def getEffectiveAccessForAuthenticatedUser(authUser: AuthUser) =
      IO.pure(List.empty[EffectiveDatabaseAccessResponse])
    def issueOtp(authUser: AuthUser, databaseId: java.util.UUID) =
      IO.raiseError(new IllegalStateException("not used"))
  }

  private given AuthMiddleware[IO, AuthUser] =
    AuthTestSupport.staticAuthMiddleware()

  test("mounts the health endpoint under /api/v1") {
    for {
      response <- Routes
        .all(stubUserService, stubAdminService, stubAccessService)
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/api/v1/health")
        )
    } yield expect(response.status == Status.Ok)
  }

  test("mounts the user sync endpoint under /api/v1") {
    for {
      response <- Routes
        .all(stubUserService, stubAdminService, stubAccessService)
        .orNotFound
        .run(
          Request[IO](Method.POST, uri"/api/v1/users/me/sync")
        )
    } yield expect(response.status == Status.Ok)
  }

  test("mounts the admin routes under /api/v1") {
    for {
      response <- Routes
        .all(stubUserService, stubAdminService, stubAccessService)
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/api/v1/admin/users")
        )
    } yield expect(response.status == Status.Ok)
  }

  test("mounts the access routes under /api/v1") {
    for {
      response <- Routes
        .all(stubUserService, stubAdminService, stubAccessService)
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/api/v1/me/effective-access")
        )
    } yield expect(response.status == Status.Ok)
  }

  test("does not expose the health endpoint without the API prefix") {
    for {
      response <- Routes
        .all(stubUserService, stubAdminService, stubAccessService)
        .orNotFound
        .run(
          Request[IO](Method.GET, uri"/health")
        )
    } yield expect(response.status == Status.NotFound)
  }
}
