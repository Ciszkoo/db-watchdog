package dbwatchdog.repository

import cats.effect.IO
import weaver.SimpleIOSuite

object RepositoriesSuite extends SimpleIOSuite {
  test("constructs default repositories") {
    val repositories = Repositories.make

    IO.pure(
      expect(repositories.users != null) and
        expect(repositories.teams != null)
    )
  }
}
