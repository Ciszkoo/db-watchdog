package dbwatchdog.repository

import java.time.Instant
import java.util.UUID

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
}
