ThisBuild / scalaVersion           := "2.13.12"
ThisBuild / crossScalaVersions     := Seq("2.12.18", "2.13.12", "3.3.1")
ThisBuild / organization           := "com.permutive"
ThisBuild / versionPolicyIntention := Compatibility.None

addCommandAlias("ci-test", "fix --check; versionPolicyCheck; mdoc; publishLocal; +test")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll")
addCommandAlias("ci-publish", "versionCheck; github; ci-release")

lazy val `fs2-pubsub` = module
  .settings(libraryDependencies ++= Dependencies.`fs2-pubsub`)
  .settings(Test / fork := true)
  .settings(Test / run / fork := true)
