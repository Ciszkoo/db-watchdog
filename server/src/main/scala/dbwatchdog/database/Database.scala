package dbwatchdog.database

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Transactor

trait Database {
  def transact[A](query: ConnectionIO[A]): IO[A]
}

object Database {
  def make(using xa: Transactor[IO]): Database = new Database {
    def transact[A](query: ConnectionIO[A]): IO[A] = query.transact(xa)
  }
}
