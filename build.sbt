name := "hearts-api"

version := "0.3"

scalaVersion := "2.13.4"

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerExposedPorts := Seq(8080)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.12",
  "com.typesafe.akka" %% "akka-stream-typed" % "2.6.12",
  "com.typesafe.akka" %% "akka-http" % "10.2.3",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.2.3",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.typelevel" %% "cats-core" % "2.2.0"
)
