import sbt.*

object Dependencies {
  object Versions {
    val pureconfig = "0.17.10"
    val catsEffect = "3.5.2"
    val http4s = "0.23.24"
    val circe = "0.14.6"
    val flyway = "11.13.0"
    val flyway4s = "1.1.0"
    val postgresql = "42.7.8"
    val doobie = "1.0.0-RC10"
    val jwt = "9.4.5"
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
    "io.circe" %% "circe-parser" % Versions.circe,

    // Database migrations
    "org.flywaydb" % "flyway-database-postgresql" % Versions.flyway,
    "com.github.geirolz" %% "fly4s" % Versions.flyway4s,

    // Database
    "org.tpolecat" %% "doobie-core" % Versions.doobie,
    "org.tpolecat" %% "doobie-postgres" % Versions.doobie,
    "org.tpolecat" %% "doobie-postgres-circe" % Versions.doobie,
    "org.tpolecat" %% "doobie-hikari" % Versions.doobie,

    // JWT for Keycloak token validation
    "com.github.jwt-scala" %% "jwt-circe" % Versions.jwt
  )
}
