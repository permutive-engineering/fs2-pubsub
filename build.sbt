ThisBuild / scalaVersion           := "2.13.12"
ThisBuild / crossScalaVersions     := Seq("2.12.18", "2.13.12", "3.3.1")
ThisBuild / organization           := "com.permutive"
ThisBuild / versionPolicyIntention := Compatibility.None

addCommandAlias("ci-test", "fix --check; versionPolicyCheck; mdoc; publishLocal; +test")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll")
addCommandAlias("ci-publish", "versionCheck; github; ci-release")

lazy val `fs2-pubsub` = module
  .settings(libraryDependencies ++= Dependencies.`fs2-pubsub`)
  .settings(libraryDependencies += scalaVersion.value.on(2, 13)(Dependencies.grpc))
  .settings(libraryDependencies += scalaVersion.value.on(3)(Dependencies.grpc))
  .settings(Test / fork := true)
  .settings(Test / run / fork := true)

lazy val `fs2-pubsub-pureconfig` = module
  .dependsOn(`fs2-pubsub`)
  .settings(libraryDependencies ++= Dependencies.`fs2-pubsub-pureconfig`)
