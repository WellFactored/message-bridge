val Http4sVersion    = "0.21.0-M6"
val CirceVersion     = "0.12.3"
val Specs2Version    = "4.1.0"
val LogbackVersion   = "1.2.3"
val tapirVersion     = "0.12.8"
val awsClientVersion = "1.11.693"

lazy val root = (project in file("."))
  .settings(
    organization := "com.wellfactored",
    name := "message-bridge",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.13.1",
    libraryDependencies ++= Seq(
      "com.amazonaws"               % "aws-java-sdk-sqs"     % awsClientVersion,
      "dev.profunktor"              %% "fs2-rabbit"          % "2.1.0",
      "io.circe"                    %% "circe-generic"       % CirceVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-core"          % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"    % tapirVersion,
      "io.chrisdavenport"           %% "log4cats-core"       % "1.0.1",
      "ch.qos.logback"              % "logback-classic"      % LogbackVersion,
      "com.fasterxml.jackson.core"  % "jackson-databind"     % "2.9.10.1"
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")
  )

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-language:higherKinds",
  "-language:postfixOps",
  "-feature",
  "-Ypartial-unification",
  "-Xfatal-warnings"
)
