val CirceVersion     = "0.12.3"
val LogbackVersion   = "1.2.3"
val awsClientVersion = "1.11.699"

lazy val root = (project in file("."))
  .settings(
    organization := "com.wellfactored",
    name := "message-bridge",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.13.1",
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion,
      "com.amazonaws" % "aws-java-sdk-sns" % awsClientVersion,
      // Override 2.6 version used by AWS SQS library as there are security issues with it
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.10.1",
      "io.circe"                   %% "circe-generic"   % CirceVersion,
      "io.circe"                   %% "circe-parser"    % CirceVersion,
      "com.rabbitmq"               % "amqp-client"      % "5.8.0",
      "dev.profunktor"             %% "fs2-rabbit"      % "2.1.1",
      "com.monovore"               %% "decline"         % "1.0.0",
      "io.chrisdavenport"          %% "log4cats-slf4j"  % "1.0.1",
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.2",
      "ch.qos.logback"             % "logback-classic"  % LogbackVersion
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
  )

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-language:higherKinds",
  "-language:postfixOps",
  "-feature",
  "-Xfatal-warnings"
)
