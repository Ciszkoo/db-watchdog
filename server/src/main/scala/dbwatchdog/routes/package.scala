package dbwatchdog

import dbwatchdog.domain.{SyncUserRequest, UpsertUserInput}

package object routes {

  extension (r: SyncUserRequest) {

    def toUpsertInput: UpsertUserInput = UpsertUserInput(
      keycloakId = r.keycloakId,
      email = r.email,
      firstName = r.firstName,
      lastName = r.lastName,
      teamName = r.team
    )
  }
}
