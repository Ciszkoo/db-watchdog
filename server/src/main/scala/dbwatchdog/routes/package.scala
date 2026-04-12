package dbwatchdog

import dbwatchdog.auth.AuthUser
import dbwatchdog.domain.AuthenticatedUserSyncInput

package object routes {

  extension (user: AuthUser) {

    def toSyncInput: AuthenticatedUserSyncInput = AuthenticatedUserSyncInput(
      keycloakId = user.sub,
      email = user.email,
      firstName = user.firstName,
      lastName = user.lastName,
      team = user.team
    )
  }
}
