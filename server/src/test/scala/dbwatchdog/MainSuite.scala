package dbwatchdog

import cats.effect.IO
import weaver.SimpleIOSuite

object MainSuite extends SimpleIOSuite {
  test("loads the default application config lazily") {
    IO.pure(
      expect(Main.appConfig.server.host == "localhost") and
        expect(Main.appConfig.server.port == 8080)
    )
  }
}
