package dbwatchdog.repository

import java.util.UUID

import dbwatchdog.domain.UpsertUserInput
import dbwatchdog.support.PostgresIntegrationSuite

object UserRepositoryIntegrationSuite extends PostgresIntegrationSuite {
  private val userRepo = UserRepository.make
  private val teamRepo = TeamRepository.make

  test("upsert inserts a new user") { db =>
    val teamName = uniqueValue("backend")
    val input = userInput(
      keycloakId = uniqueValue("kc"),
      email = s"${uniqueValue("john")}@example.com",
      teamName = teamName
    )

    for {
      team <- db.transact(teamRepo.create(teamName))
      persisted <- db.transact(userRepo.upsert(input, team.id))
    } yield expect(persisted.keycloakId == input.keycloakId) and
      expect(persisted.email == input.email) and
      expect(persisted.teamId == team.id)
  }

  test("create inserts a new user for an existing team") { db =>
    val teamName = uniqueValue("backend")
    val keycloakId = uniqueValue("kc")
    val email = s"${uniqueValue("created")}@example.com"

    for {
      team <- db.transact(teamRepo.create(teamName))
      persisted <- db.transact(
        userRepo.create(
          keycloakId = keycloakId,
          email = email,
          firstName = "Create",
          lastName = "User",
          teamId = team.id
        )
      )
    } yield expect(persisted.keycloakId == keycloakId) and
      expect(persisted.email == email) and
      expect(persisted.teamId == team.id)
  }

  test("update changes the mutable user fields") { db =>
    val firstTeamName = uniqueValue("backend")
    val secondTeamName = uniqueValue("platform")
    val keycloakId = uniqueValue("kc")

    for {
      firstTeam <- db.transact(teamRepo.create(firstTeamName))
      secondTeam <- db.transact(teamRepo.create(secondTeamName))
      created <- db.transact(
        userRepo.create(
          keycloakId = keycloakId,
          email = s"${uniqueValue("updated")}@example.com",
          firstName = "Before",
          lastName = "Update",
          teamId = firstTeam.id
        )
      )
      updated <- db.transact(
        userRepo.update(
          id = created.id,
          firstName = "After",
          lastName = "Update",
          teamId = secondTeam.id
        )
      )
    } yield expect(updated.id == created.id) and
      expect(updated.firstName == "After") and
      expect(updated.lastName == "Update") and
      expect(updated.teamId == secondTeam.id)
  }

  test("upsert updates an existing user for the same keycloak id") { db =>
    val keycloakId = uniqueValue("kc")
    val firstTeamName = uniqueValue("backend")
    val secondTeamName = uniqueValue("platform")
    val originalInput = userInput(
      keycloakId = keycloakId,
      email = s"${uniqueValue("john")}@example.com",
      teamName = firstTeamName
    )
    val updatedInput = userInput(
      keycloakId = keycloakId,
      email = s"${uniqueValue("john-updated")}@example.com",
      firstName = "Johnny",
      lastName = "Tester",
      teamName = secondTeamName
    )

    for {
      firstTeam <- db.transact(teamRepo.create(firstTeamName))
      secondTeam <- db.transact(teamRepo.create(secondTeamName))
      original <- db.transact(userRepo.upsert(originalInput, firstTeam.id))
      updated <- db.transact(userRepo.upsert(updatedInput, secondTeam.id))
    } yield expect(updated.id == original.id) and
      expect(updated.email == updatedInput.email) and
      expect(updated.firstName == updatedInput.firstName) and
      expect(updated.lastName == updatedInput.lastName) and
      expect(updated.teamId == secondTeam.id)
  }

  test("findByKeycloakId returns the persisted user") { db =>
    val teamName = uniqueValue("backend")
    val input = userInput(
      keycloakId = uniqueValue("kc"),
      email = s"${uniqueValue("john")}@example.com",
      teamName = teamName
    )

    for {
      team <- db.transact(teamRepo.create(teamName))
      persisted <- db.transact(userRepo.upsert(input, team.id))
      found <- db.transact(userRepo.findByKeycloakId(input.keycloakId))
    } yield expect(found == persisted)
  }

  private def userInput(
      keycloakId: String = "kc-123",
      email: String = "john@example.com",
      firstName: String = "John",
      lastName: String = "Doe",
      teamName: String = "backend"
  ): UpsertUserInput =
    UpsertUserInput(
      keycloakId = keycloakId,
      email = email,
      firstName = firstName,
      lastName = lastName,
      teamName = teamName
    )

  private def uniqueValue(prefix: String): String =
    s"$prefix-${UUID.randomUUID().toString.take(8)}"
}
