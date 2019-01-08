
import sbtrelease.ReleaseStateTransformations._

lazy val `fs2-google-pubsub` = (project in file("google-pubsub"))
  .settings(
    Common.settings,
    name := "fs2-google-pubsub",
    libraryDependencies ++= Dependencies.common,
  )

lazy val `fs2-google-pubsub-http` = (project in file("google-pubsub-http"))
  .dependsOn(`fs2-google-pubsub`)
  .settings(
    Common.settings,
    name := "fs2-google-pubsub-http",
    libraryDependencies ++= Dependencies.common,
    libraryDependencies ++= Dependencies.http,
  )

lazy val `fs2-google-pubsub-grpc` = (project in file("google-pubsub-grpc"))
  .dependsOn(`fs2-google-pubsub`)
  .settings(
    Common.settings,
    name := "fs2-google-pubsub-grpc",
    libraryDependencies ++= Dependencies.common,
    libraryDependencies ++= Dependencies.googlePubsub,
  )

lazy val `fs2-pubsub-root` = project
  .in(file("."))
  .settings(Common.settings)
  .settings(Common.noPublish)
  .settings(crossScalaVersions := Nil)
  .aggregate(
    `fs2-google-pubsub`,
    `fs2-google-pubsub-http`,
    `fs2-google-pubsub-grpc`,
  )

releaseCrossBuild := false
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("publishSigned"),
  setNextVersion,
  commitNextVersion,
  releaseStepCommand("sonatypeReleaseAll"),
  pushChanges,
)