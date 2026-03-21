package dbwatchdog.service

import cats.effect.IO
import weaver.SimpleIOSuite

import dbwatchdog.database.Database
import dbwatchdog.repository.Repositories

object ServicesSuite extends SimpleIOSuite {
  test("constructs services from repositories and database") {
    val services = Services.make(
      repos = Repositories.make,
      db = new Database {
        def transact[A](query: doobie.ConnectionIO[A]): IO[A] =
          IO.raiseError(new IllegalStateException("not used in this test"))
      }
    )

    IO.pure(expect(services.users != null))
  }
}
