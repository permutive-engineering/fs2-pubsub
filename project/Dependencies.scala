import sbt._

object Dependencies {

  object Resolvers {
    lazy val typesafe = "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
    lazy val sonatypeReleases = Resolver.sonatypeRepo("releases")
    lazy val snapshots = Resolver.sonatypeRepo("snapshots")

    lazy val all =  Seq(typesafe, sonatypeReleases, snapshots)
  }

  object Versions {
    lazy val scala213      = "2.13.0-M5"
    lazy val scala212      = "2.12.8"
    lazy val scala211      = "2.11.11"

    lazy val cats       = "1.5.0"
    lazy val catsEffect = "1.1.0"

    lazy val pubsub     = "1.57.0"
    lazy val fs2        = "1.0.2"

    lazy val avro       = "1.8.2"
    lazy val schemaRepo = "0.1.3"

    lazy val http4s     = "0.20.0-M4"
    lazy val jsoniter   = "0.37.7"
    lazy val jwt        = "3.4.1"

    lazy val log4cats   = "0.2.0"

    lazy val scalatest  = "3.0.5"
    lazy val scalamock  = "4.1.0"
    lazy val containers = "0.22.0"
  }

  object Libraries {
    lazy val cats                  = "org.typelevel"                 %% "cats-core"                           % Versions.cats
    lazy val catsEffect            = "org.typelevel"                 %% "cats-effect"                         % Versions.catsEffect
    lazy val pubsub                = "com.google.cloud"              %  "google-cloud-pubsub"                 % Versions.pubsub
    lazy val fs2                   = "co.fs2"                        %% "fs2-core"                            % Versions.fs2

    lazy val http4s                = "org.http4s"                    %% "http4s-dsl"                          % Versions.http4s
    lazy val http4sClient          = "org.http4s"                    %% "http4s-client"                       % Versions.http4s
    lazy val http4sAsyncClient     = "org.http4s"                    %% "http4s-async-http-client"            % Versions.http4s

    lazy val jsoniterCore          = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"         % Versions.jsoniter % Compile
    lazy val jsoniterMacros        = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros"       % Versions.jsoniter % Provided // required only in compile-time

    lazy val jwt                   = "com.auth0"                     %  "java-jwt"                            % Versions.jwt

    lazy val log4cats              = "io.chrisdavenport"             %% "log4cats-slf4j"                      % Versions.log4cats

    lazy val scalactic             = "org.scalactic"                 %% "scalactic"                           % Versions.scalatest % Test
    lazy val scalatest             = "org.scalatest"                 %% "scalatest"                           % Versions.scalatest % Test
    lazy val scalamock             = "org.scalamock"                 %% "scalamock"                           % Versions.scalamock % Test
    lazy val containers            = "com.dimafeng"                  %% "testcontainers-scala"                % Versions.containers % Test
  }

  val test = Seq(
    Libraries.scalactic,
    Libraries.scalatest,
    Libraries.scalamock,
    Libraries.containers,
  )

  val common = Seq(
    Libraries.cats,
    Libraries.catsEffect,
    Libraries.fs2,
  ) ++ test

  val http = Seq(
    Libraries.http4s,
    Libraries.http4sClient,
    Libraries.jsoniterCore,
    Libraries.jsoniterMacros,
    Libraries.jwt,
    Libraries.log4cats,

    Libraries.http4sAsyncClient % Test,
  )

  val googlePubsub = Seq(
    Libraries.pubsub,
  )

}