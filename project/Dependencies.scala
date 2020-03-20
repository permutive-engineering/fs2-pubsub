import sbt._

object Dependencies {
  object Versions {
    val scala212      = "2.12.10"
    val scala213      = "2.13.1"
    val catsCore      = "2.1.1"
    val effect        = "2.1.2"
    val fs2           = "2.3.0"
    val http4s        = "0.21.0"
    val log4cats      = "1.0.1"
    val jwt           = "3.9.0"
    val jsoniter      = "2.1.6"
    val gcp           = "1.102.0"
    val scalatest     = "3.1.0"
    val scalatestPlus = "3.1.0.1"
    val testContainers = "0.34.1"
  }

  object Libraries {
    val catsCore      = "org.typelevel" %% "cats-core"      % Versions.catsCore
    val alleyCatsCore = "org.typelevel" %% "alleycats-core" % Versions.catsCore
    val effect        = "org.typelevel" %% "cats-effect"    % Versions.effect
    val fs2           = "co.fs2"        %% "fs2-core"       % Versions.fs2

    val http4sDsl    = "org.http4s" %% "http4s-dsl"           % Versions.http4s
    val http4sClient = "org.http4s" %% "http4s-client"        % Versions.http4s
    val http4sHttp   = "org.http4s" %% "http4s-okhttp-client" % Versions.http4s % Test

    val log4cats      = "io.chrisdavenport" %% "log4cats-core"  % Versions.log4cats
    val log4catsSlf4j = "io.chrisdavenport" %% "log4cats-slf4j" % Versions.log4cats

    val jwt = "com.auth0"        % "java-jwt"            % Versions.jwt
    val gcp = "com.google.cloud" % "google-cloud-pubsub" % Versions.gcp

    val jsoniterCore   = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % Versions.jsoniter % Compile
    val jsoniterMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % Versions.jsoniter % Provided

    val testContainers = "com.dimafeng"      %% "testcontainers-scala-scalatest" % Versions.testContainers % Test
    val scalatest      = "org.scalatest"     %% "scalatest"                      % Versions.scalatest      % Test
    val scalatestPlus  = "org.scalatestplus" %% "scalacheck-1-14"                % Versions.scalatestPlus  % Test
  }

  lazy val testsDependencies = Seq(
    Libraries.scalatest,
    Libraries.scalatestPlus,
    Libraries.http4sHttp,
    Libraries.log4cats,
    Libraries.log4catsSlf4j,
  )

  lazy val commonDependencies = Seq(
    Libraries.catsCore,
    Libraries.effect,
    Libraries.fs2,
  )

  lazy val httpDependencies = Seq(
    Libraries.alleyCatsCore,
    Libraries.http4sDsl,
    Libraries.http4sClient,
    Libraries.log4cats,
    Libraries.jwt,
    Libraries.jsoniterCore,
    Libraries.jsoniterMacros,
  )

  lazy val grpcDependencies = Seq(
    Libraries.gcp,
  )
}
