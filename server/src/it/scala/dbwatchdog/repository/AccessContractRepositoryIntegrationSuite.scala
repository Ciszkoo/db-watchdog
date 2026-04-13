package dbwatchdog.repository

import java.time.Instant
import java.util.UUID

import doobie.implicits.*
import doobie.postgres.implicits.*

import dbwatchdog.domain.{
  CreateDatabase,
  CreateDatabaseSessionInput,
  CreateTemporaryAccessCredentialInput,
  UpsertTeamDatabaseGrantInput,
  UpsertUserDatabaseAccessExtensionInput,
  UpsertUserInput
}
import dbwatchdog.support.PostgresIntegrationSuite

object AccessContractRepositoryIntegrationSuite
    extends PostgresIntegrationSuite {
  private val teamRepo = TeamRepository.make
  private val userRepo = UserRepository.make
  private val databaseRepo = DatabaseRepository.make
  private val grantRepo = TeamDatabaseGrantRepository.make
  private val extensionRepo = UserDatabaseAccessExtensionRepository.make
  private val credentialRepo = TemporaryAccessCredentialRepository.make
  private val sessionRepo = DatabaseSessionRepository.make

  test("team grants upsert on the natural key") { db =>
    withCleanDb(db) { db =>
      val seeded = seedAccessGraph(db)

      for {
        ids <- seeded
        first <- db.transact(
          grantRepo.upsert(
            UpsertTeamDatabaseGrantInput(ids.teamId, ids.databaseId)
          )
        )
        second <- db.transact(
          grantRepo.upsert(
            UpsertTeamDatabaseGrantInput(ids.teamId, ids.databaseId)
          )
        )
      } yield expect(first.id == second.id) and
        expect(first.teamId == ids.teamId) and
        expect(first.databaseId == ids.databaseId)
    }
  }

  test("user access extensions upsert on the natural key") { db =>
    withCleanDb(db) { db =>
      val seeded = seedAccessGraph(db)
      val initialExpiry = Instant.parse("2026-01-01T00:00:00Z")
      val updatedExpiry = Instant.parse("2026-02-01T00:00:00Z")

      for {
        ids <- seeded
        first <- db.transact(
          extensionRepo.upsert(
            UpsertUserDatabaseAccessExtensionInput(
              ids.userId,
              ids.databaseId,
              Some(initialExpiry)
            )
          )
        )
        second <- db.transact(
          extensionRepo.upsert(
            UpsertUserDatabaseAccessExtensionInput(
              ids.userId,
              ids.databaseId,
              Some(updatedExpiry)
            )
          )
        )
      } yield expect(first.id == second.id) and
        expect(second.expiresAt.nonEmpty)
    }
  }

  test("temporary credentials and sessions persist the shared proxy contract") {
    db =>
      withCleanDb(db) { db =>
        val seeded = seedAccessGraph(db)
        val expiresAt = Instant.parse("2026-03-01T00:00:00Z")
        val startedAt = Instant.parse("2026-03-01T00:05:00Z")
        val endedAt = Instant.parse("2026-03-01T00:10:00Z")

        for {
          ids <- seeded
          credential <- db.transact(
            credentialRepo.create(
              CreateTemporaryAccessCredentialInput(
                ids.userId,
                ids.databaseId,
                "otp-hash",
                expiresAt
              )
            )
          )
          markedUsed <- db.transact(
            credentialRepo.markUsed(credential.id, startedAt)
          )
          session <- db.transact(
            sessionRepo.create(
              CreateDatabaseSessionInput(
                ids.userId,
                ids.databaseId,
                credential.id,
                "127.0.0.1:5432",
                startedAt
              )
            )
          )
          finished <- db.transact(
            sessionRepo.markEnded(session.id, endedAt, 120L, 240L)
          )
        } yield expect(markedUsed.usedAt.nonEmpty) and
          expect(session.credentialId == credential.id) and
          expect(finished.endedAt.nonEmpty) and
          expect(finished.bytesSent.contains(120L)) and
          expect(finished.bytesReceived.contains(240L))
      }
  }

  test("team, user, and database repositories list and find mirrored records") {
    db =>
      withCleanDb(db) { db =>
        val seeded = seedAccessGraph(db)

        for {
          ids <- seeded
          teams <- db.transact(teamRepo.list)
          users <- db.transact(userRepo.list)
          databases <- db.transact(databaseRepo.list)
          team <- db.transact(teamRepo.findById(ids.teamId))
          user <- db.transact(userRepo.findById(ids.userId))
          database <- db.transact(databaseRepo.findById(ids.databaseId))
          selectedDatabases <- db.transact(
            databaseRepo.findByIds(Set(ids.databaseId))
          )
        } yield expect(teams.exists(_.id == ids.teamId)) and
          expect(users.exists(_.id == ids.userId)) and
          expect(databases.exists(_.id == ids.databaseId)) and
          expect(team.exists(_.id == ids.teamId)) and
          expect(user.exists(_.id == ids.userId)) and
          expect(database.exists(_.id == ids.databaseId)) and
          expect(selectedDatabases.map(_.id) == List(ids.databaseId))
      }
  }

  test(
    "grant and extension lookup queries return the effective access inputs and delete removes them"
  ) { db =>
    withCleanDb(db) { db =>
      val seeded = seedAccessGraph(db)
      val futureExpiry = Instant.parse("2026-04-01T00:00:00Z")
      val expiredAt = Instant.parse("2020-04-01T00:00:00Z")

      for {
        ids <- seeded
        secondDatabase <- db.transact(
          databaseRepo.insert(
            CreateDatabase(
              engine = "postgres",
              host = "db-secondary.local",
              port = 5433,
              technicalUser = "technical_secondary",
              technicalPassword = "secret",
              databaseName = "secondary"
            )
          )
        )
        _ <- db.transact(
          grantRepo.upsert(
            UpsertTeamDatabaseGrantInput(ids.teamId, ids.databaseId)
          )
        )
        _ <- db.transact(
          extensionRepo.upsert(
            UpsertUserDatabaseAccessExtensionInput(
              ids.userId,
              ids.databaseId,
              Some(futureExpiry)
            )
          )
        )
        _ <- db.transact(
          extensionRepo.upsert(
            UpsertUserDatabaseAccessExtensionInput(
              ids.userId,
              secondDatabase.id,
              Some(expiredAt)
            )
          )
        )
        grantedDatabaseIds <- db.transact(
          grantRepo.findDatabaseIdsByTeamId(ids.teamId)
        )
        activeExtensions <- db.transact(
          extensionRepo.findActiveByUserId(
            ids.userId,
            Instant.parse("2026-03-01T00:00:00Z")
          )
        )
        _ <- db.transact(
          grantRepo.delete(ids.teamId, ids.databaseId)
        )
        _ <- db.transact(
          extensionRepo.delete(ids.userId, ids.databaseId)
        )
        remainingGrantCount <- db.transact(
          sql"""
              SELECT COUNT(*)
              FROM team_database_grants
              WHERE team_id = ${ids.teamId}
                AND database_id = ${ids.databaseId}
            """.query[Int].unique
        )
        remainingExtensionCount <- db.transact(
          sql"""
              SELECT COUNT(*)
              FROM user_database_access_extensions
              WHERE user_id = ${ids.userId}
                AND database_id = ${ids.databaseId}
            """.query[Int].unique
        )
      } yield expect(grantedDatabaseIds == List(ids.databaseId)) and
        expect(activeExtensions.map(_.databaseId) == List(ids.databaseId)) and
        expect(remainingGrantCount == 0) and
        expect(remainingExtensionCount == 0)
    }
  }

  test(
    "temporary credential invalidation marks only active credentials as used"
  ) { db =>
    withCleanDb(db) { db =>
      val seeded = seedAccessGraph(db)
      val now = Instant.parse("2026-05-01T00:10:00Z")
      val activeExpiry = Instant.parse("2026-05-01T00:20:00Z")
      val expiredAt = Instant.parse("2026-05-01T00:05:00Z")
      val existingUsedAt = Instant.parse("2026-05-01T00:00:00Z")

      for {
        ids <- seeded
        sameDatabaseActiveOne <- db.transact(
          credentialRepo.create(
            CreateTemporaryAccessCredentialInput(
              ids.userId,
              ids.databaseId,
              "hash-active-1",
              activeExpiry
            )
          )
        )
        sameDatabaseActiveTwo <- db.transact(
          credentialRepo.create(
            CreateTemporaryAccessCredentialInput(
              ids.userId,
              ids.databaseId,
              "hash-active-2",
              activeExpiry
            )
          )
        )
        sameDatabaseExpired <- db.transact(
          credentialRepo.create(
            CreateTemporaryAccessCredentialInput(
              ids.userId,
              ids.databaseId,
              "hash-expired",
              expiredAt
            )
          )
        )
        sameDatabaseAlreadyUsed <- db.transact(
          credentialRepo.create(
            CreateTemporaryAccessCredentialInput(
              ids.userId,
              ids.databaseId,
              "hash-used",
              activeExpiry
            )
          )
        )
        _ <- db.transact(
          credentialRepo.markUsed(sameDatabaseAlreadyUsed.id, existingUsedAt)
        )
        otherDatabase <- db.transact(
          databaseRepo.insert(
            CreateDatabase(
              engine = "postgres",
              host = "db-other.local",
              port = 5434,
              technicalUser = "technical_other",
              technicalPassword = "secret",
              databaseName = "other"
            )
          )
        )
        otherDatabaseCredential <- db.transact(
          credentialRepo.create(
            CreateTemporaryAccessCredentialInput(
              ids.userId,
              otherDatabase.id,
              "hash-other-db",
              activeExpiry
            )
          )
        )
        updatedCount <- db.transact(
          credentialRepo.invalidateActiveForUserAndDatabase(
            ids.userId,
            ids.databaseId,
            now
          )
        )
        activeOneUsedAt <- db.transact(
          credentialUsedAt(sameDatabaseActiveOne.id)
        )
        activeTwoUsedAt <- db.transact(
          credentialUsedAt(sameDatabaseActiveTwo.id)
        )
        expiredUsedAt <- db.transact(credentialUsedAt(sameDatabaseExpired.id))
        alreadyUsedAt <- db.transact(
          credentialUsedAt(sameDatabaseAlreadyUsed.id)
        )
        otherDatabaseUsedAt <- db.transact(
          credentialUsedAt(otherDatabaseCredential.id)
        )
      } yield expect(updatedCount == 2) and
        expect(activeOneUsedAt.nonEmpty) and
        expect(activeTwoUsedAt.nonEmpty) and
        expect(expiredUsedAt.isEmpty) and
        expect(alreadyUsedAt.nonEmpty) and
        expect(otherDatabaseUsedAt.isEmpty)
    }
  }

  private def seedAccessGraph(
      db: dbwatchdog.support.IntegrationDb
  ) =
    for {
      suffix <- cats.effect.IO(UUID.randomUUID().toString.take(8))
      team <- db.transact(teamRepo.create(s"team-$suffix"))
      database <- db.transact(
        databaseRepo.insert(
          CreateDatabase(
            engine = "postgres",
            host = s"db-$suffix.local",
            port = 5432,
            technicalUser = s"technical_$suffix",
            technicalPassword = "secret",
            databaseName = s"app_$suffix"
          )
        )
      )
      user <- db.transact(
        userRepo.upsert(
          UpsertUserInput(
            keycloakId = s"kc-$suffix",
            email = s"user-$suffix@example.com",
            firstName = "Jane",
            lastName = "Doe",
            teamName = team.name
          ),
          team.id
        )
      )
    } yield SeededIds(team.id, user.id, database.id)

  private final case class SeededIds(
      teamId: UUID,
      userId: UUID,
      databaseId: UUID
  )

  private def credentialUsedAt(
      credentialId: UUID
  ) =
    sql"""
      SELECT used_at
      FROM temporary_access_credentials
      WHERE id = $credentialId
    """.query[Option[Instant]].unique
}
