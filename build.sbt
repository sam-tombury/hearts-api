name := "hearts-api"

version := "0.4"

scalaVersion := "2.13.4"
scalacOptions += "-Ymacro-annotations"

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerExposedPorts := Seq(8080)
dockerBaseImage := "openjdk:11-jre-slim"
dockerUpdateLatest := true

val Http4sVersion = "0.21.16"
val CirceVersion = "0.13.0"
val ZioVersion = "1.0.4-2"
val ZioInteropCatsVersion = "2.3.1.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.12",
  "com.typesafe.akka" %% "akka-stream-typed" % "2.6.12",
  "com.typesafe.akka" %% "akka-http" % "10.2.3",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.2.3",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.typelevel" %% "cats-core" % "2.2.0",
  "org.http4s" %% "http4s-blaze-server" % Http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % Http4sVersion,
  "org.http4s" %% "http4s-circe" % Http4sVersion,
  "org.http4s" %% "http4s-dsl" % Http4sVersion,
  "io.circe" %% "circe-generic" % CirceVersion,
  "dev.zio" %% "zio" % ZioVersion,
  "dev.zio" %% "zio-interop-cats" % ZioInteropCatsVersion,
  "dev.zio" %% "zio-macros" % ZioVersion
)
