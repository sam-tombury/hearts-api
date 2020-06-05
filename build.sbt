name := "Hearts"

version := "0.1"

scalaVersion := "2.13.2"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.5",
  "com.typesafe.akka" %% "akka-stream-typed" % "2.6.5",
  "com.typesafe.akka" %% "akka-http"   % "10.1.12",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.12",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.typelevel" %% "cats-core" % "2.0.0"
)