import sbt._
import Keys._
import com.typesafe.sbt.SbtPgp.autoImport._
import sbtrelease._

object BuildSettings {
  val buildOrganization = "com.github.gilbertw1"
  val buildVersion      = "0.2.1"
  val buildScalaVersion = "2.11.11"
//  val buildCrossScalaVersions = Seq("2.11.11", "2.12.3")

  val buildSettings = Seq (
    organization       := buildOrganization,
    version            := buildVersion,
    scalaVersion       := buildScalaVersion,
//    crossScalaVersions := buildCrossScalaVersions,
    publishMavenStyle  := true,
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
      <url>https://github.com/gilbertw1/slack-scala-client</url>
      <licenses>
        <license>
          <name>MIT</name>
          <url>https://opensource.org/licenses/MIT</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <url>git@github.com:gilbertw1/slack-scala-client.git</url>
        <connection>scm:git:git@github.com:gilbertw1/slack-scala-client.git</connection>
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

object Resolvers {
  val typesafeRepo = "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"
}

object Dependencies {
  val akkaVersion = "2.4.8"
  val sprayVersion = "1.3.3"
  val playVersion = "2.4.6"

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % akkaVersion
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % akkaVersion

  val scalaAsync = "org.scala-lang.modules" %% "scala-async" % "0.9.6"
  val dispatch = "net.databinder.dispatch" %% "dispatch-core" % "0.11.3"
  val playJson = "com.typesafe.play" %% "play-json" % playVersion
  val sprayWebsocket = "com.wandoulabs.akka" %% "spray-websocket" % "0.1.4"

  val scalatest = "org.scalatest" %% "scalatest" % "3.0.0" % "test"

  val akkaDependencies = Seq(akkaActor, akkaSlf4j)
  val miscDependencies = Seq(playJson, scalaAsync, dispatch, sprayWebsocket)
  val testDependencies = Seq(scalatest)

  val allDependencies = akkaDependencies ++ miscDependencies ++ testDependencies
}

object SlackScalaClient extends Build {
  import Resolvers._
  import BuildSettings._
  import Defaults._

  lazy val slackScalaClient =
    Project ("slack-scala-client", file("."))
      .settings ( buildSettings : _* )
      .settings ( resolvers ++= Seq(typesafeRepo) )
      .settings ( libraryDependencies ++= Dependencies.allDependencies )
      .settings ( dependencyOverrides += "io.spray" %% "spray-can" % Dependencies.sprayVersion)
      .settings ( scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xlint", "-Xfatal-warnings", "-feature") )

}
