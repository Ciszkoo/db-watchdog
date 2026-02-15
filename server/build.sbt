import Dependencies.*

val scala3Version = "3.8.1"

ThisBuild / semanticdbEnabled := true
//ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
//ThisBuild / scalafixScalaBinaryVersion := "3"

//addCompilerPlugin(scalafixSemanticdb)

lazy val root = project
  .in(file("."))
  .settings(
    name := "server",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= dependencies,
    scalacOptions ++= Seq(
      "-Wunused:imports"
    )
  )
  //.enablePlugins(ScalafixPlugin)
