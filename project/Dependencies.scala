import sbt._
import sbt.Keys._

object Dependencies {

  lazy val documentation = Seq(
    ("org.scalameta" %% "mdoc" % mdoc.BuildInfo.version).excludeAll(
      ExclusionRule(organization = "com.thesamet.scalapb", name = "lenses_2.13"),
      ExclusionRule(organization = "com.thesamet.scalapb", name = "scalapb-runtime_2.13")
    ),
    "com.permutive" %% "gcp-auth"            % "2.1.0",
    "org.http4s"    %% "http4s-ember-client" % "0.23.32"
  )

  lazy val `http4s-grpc` = "io.chrisdavenport" %% "http4s-grpc" % "0.0.4"

  lazy val grpc = Seq(
    "com.google.api.grpc" % "proto-google-cloud-pubsub-v1" % "1.125.1",
    "com.google.api.grpc" % "proto-google-common-protos"   % "2.62.0",
    "com.google.protobuf" % "protobuf-java"                % "4.33.0"
  ).map(_ % "protobuf-src" intransitive ()) ++ Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  )

  lazy val `fs2-pubsub` = Seq(
    "co.fs2"        %% "fs2-core"                % "3.12.2",
    "com.permutive" %% "common-types-gcp-http4s" % "1.2.1",
    "io.circe"      %% "circe-parser"            % "0.14.15",
    "org.http4s"    %% "http4s-circe"            % "0.23.32",
    "org.http4s"    %% "http4s-client"           % "0.23.32",
    "org.http4s"    %% "http4s-dsl"              % "0.23.32"
  ) ++ Seq(
    "com.dimafeng"  %% "testcontainers-scala-munit" % "0.43.6",
    "com.permutive" %% "gcp-auth"                   % "2.1.0",
    "org.http4s"    %% "http4s-ember-client"        % "0.23.32",
    "org.slf4j"      % "slf4j-nop"                  % "2.0.17",
    "org.typelevel" %% "munit-cats-effect"          % "2.1.0"
  ).map(_ % Test)

  lazy val `fs2-pubsub-pureconfig` = Seq(
    "com.github.pureconfig" %% "pureconfig-http4s"           % "0.17.9",
    "com.permutive"         %% "common-types-gcp-pureconfig" % "1.2.1"
  )

}
