import sbt._
import sbt.Keys._

object Dependencies {

  lazy val `fs2-pubsub` = Seq(
    "co.fs2"        %% "fs2-core"                % "3.9.4",
    "com.permutive" %% "common-types-gcp-http4s" % "0.0.2",
    "io.circe"      %% "circe-parser"            % "0.14.6",
    "org.http4s"    %% "http4s-circe"            % "0.23.16",
    "org.http4s"    %% "http4s-client"           % "0.23.16",
    "org.http4s"    %% "http4s-dsl"              % "0.23.16"
  ) ++ Seq(
    "com.dimafeng"  %% "testcontainers-scala-munit" % "0.41.0",
    "com.permutive" %% "gcp-auth"                   % "0.1.0",
    "org.http4s"    %% "http4s-ember-client"        % "0.23.25",
    "org.slf4j"      % "slf4j-nop"                  % "2.0.10",
    "org.typelevel" %% "munit-cats-effect"          % "2.0.0-M4"
  ).map(_ % Test)

}
