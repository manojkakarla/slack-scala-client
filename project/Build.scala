import sbt.Keys._
import sbt._

object BuildSettings {
  val buildOrganization = "com.github.slack-scala-client"
  val buildVersion      = "0.2.10"
  val buildScalaVersion = "2.11.12"

  val settings = Seq (
    organization       := buildOrganization,
    version            := buildVersion,
    scalaVersion       := buildScalaVersion,
    crossScalaVersions :=  Seq("2.12.11", scalaVersion.value, "2.13.2"),
    publishMavenStyle  := true,
    credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
    publishTo          := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    pomExtra := (
      <url>https://github.com/slack-scala-client/slack-scala-client</url>
      <licenses>
        <license>
          <name>MIT</name>
          <url>https://opensource.org/licenses/MIT</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <url>git@github.com:slack-scala-client/slack-scala-client.git</url>
        <connection>scm:git:git@github.com:slack-scala-client/slack-scala-client.git</connection>
      </scm>
      <developers>
        <developer>
          <id>gilbertw1</id>
          <name>Bryan Gilbert</name>
          <url>http://bryangilbert.com</url>
        </developer>
      </developers>)
  )
}

object Dependencies {
  val akkaVersion = "2.5.23"

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % akkaVersion
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % akkaVersion
  val akkaHttp = "com.typesafe.akka" %% "akka-http-core" % "10.1.10"

  val playJson = "com.typesafe.play" %% "play-json" % "2.5.19"

  val scalatest = "org.scalatest" %% "scalatest" % "3.2.0" % Test

  val jodaConvert = "org.joda" % "joda-convert" % "2.2.1" // https://stackoverflow.com/a/13856382/118587

  val akkaDependencies = Seq(akkaHttp, akkaActor, akkaStream)
  val miscDependencies = Seq(playJson, jodaConvert)
  val testDependencies = Seq(scalatest)

  val allDependencies = akkaDependencies ++ miscDependencies ++ testDependencies
}
