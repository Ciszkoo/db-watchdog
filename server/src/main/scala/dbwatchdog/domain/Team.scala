package dbwatchdog.domain

import java.time.Instant
import java.util.UUID

import dbwatchdog.database.Persisted

final case class Team(
    id: UUID,
    name: String,
    createdAt: Instant,
    updatedAt: Instant
) extends Persisted[UUID]
