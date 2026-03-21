import Dependencies.*

val scala3Version = "3.8.1"
lazy val It = config("it") extend Test

ThisBuild / semanticdbEnabled := true
//ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
//ThisBuild / scalafixScalaBinaryVersion := "3"

//addCompilerPlugin(scalafixSemanticdb)

lazy val root = project
  .in(file("."))
  .configs(It)
  .settings(
    inConfig(It)(Defaults.testSettings),
    name := "server",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= dependencies ++ testDependencies,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
    scalacOptions ++= Seq(
      "-Wunused:imports"
    ),
    coverageFailOnMinimum := true,
    coverageMinimumStmtTotal := 100,
    coverageMinimumBranchTotal := 100,
    Test / parallelExecution := false,
    It / parallelExecution := false
  )
  //.enablePlugins(ScalafixPlugin)
