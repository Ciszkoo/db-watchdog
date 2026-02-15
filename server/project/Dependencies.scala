import sbt.*

object Dependencies {
  object Versions {
    val pureconfig = "0.17.10"
    val catsEffect = "3.5.2"
    val http4s = "0.23.24"
    val circe = "0.14.6"
  }

  val dependencies = Seq(
    // Configuration
    "com.github.pureconfig" %% "pureconfig-core" % Versions.pureconfig,
    "com.github.pureconfig" %% "pureconfig-cats" % Versions.pureconfig,

    // Cats Effects
    "org.typelevel" %% "cats-effect" % Versions.catsEffect,

    // Http4s
    "org.http4s" %% "http4s-ember-server" % Versions.http4s,
    "org.http4s" %% "http4s-circe" % Versions.http4s,
    "org.http4s" %% "http4s-dsl" % Versions.http4s,

    // Circe for JSON
    "io.circe" %% "circe-core" % Versions.circe,
    "io.circe" %% "circe-generic" % Versions.circe,
    "io.circe" %% "circe-parser" % Versions.circe
  )
}
