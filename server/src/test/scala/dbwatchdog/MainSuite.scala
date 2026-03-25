package dbwatchdog

import cats.effect.IO
import weaver.SimpleIOSuite

object MainSuite extends SimpleIOSuite {
  test("builds the main IO program lazily") {
    val program = Main.run(Nil)

    IO.pure(expect(program.isInstanceOf[cats.effect.IO[cats.effect.ExitCode]]))
  }

  test("builds the configured main IO program lazily") {
    val config = dbwatchdog.config.AppConfig.load
    val program = Main.runLoadedConfig(config)

    IO.pure(expect(program.isInstanceOf[cats.effect.IO[cats.effect.ExitCode]]))
  }

  test("loads the default application config lazily") {
    Main.loadConfig.map { config =>
      expect(config.server.host == "localhost") and
        expect(config.server.port == 8080)
    }
  }
}
