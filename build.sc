import mill._
import mill.scalalib._
import mill.scalalib.publish._

object Dependencies {

  object version {
    val scala212 = "2.12.8"
    val scala213 = "2.13.0"

    val cross    = List(scala212, scala213)

    val catsCore = "2.0.0-M4"
    val effect   = "2.0.0-M4"
    val fs2      = "1.1.0-M1"
    val http4s   = "0.21.0-M2"
    val log4cats = "0.4.0-M2"
    val jwt      = "3.8.1"
    val jsoniter = "0.52.2"
    val gcp      = "1.77.0"

    val scalatest = "3.1.0-SNAP13"
    val scalatestPlus = "1.0.0-SNAP8"
  }

  object libraries {
    val catsCore       = ivy"org.typelevel::cats-core:${version.catsCore}"
    val alleyCatsCore  = ivy"org.typelevel::alleycats-core:${version.catsCore}"
    val effect         = ivy"org.typelevel::cats-effect:${version.effect}"
    val fs2            = ivy"co.fs2::fs2-core:${version.fs2}"

    val http4sDsl      = ivy"org.http4s::http4s-dsl:${version.http4s}"
    val http4sClient   = ivy"org.http4s::http4s-client:${version.http4s}"
    val http4sHttp     = ivy"org.http4s::http4s-okhttp-client:${version.http4s}"

    val log4cats       = ivy"io.chrisdavenport::log4cats-core:${version.log4cats}"
    val log4catsSlf4j  = ivy"io.chrisdavenport::log4cats-slf4j:${version.log4cats}"

    val jwt            = ivy"com.auth0:java-jwt:${version.jwt}"
    val gcp            = ivy"com.google.cloud:google-cloud-pubsub:${version.gcp}"

    val jsoniterCore   = ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${version.jsoniter}"
    val jsoniterMacros = ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${version.jsoniter}"

    val scalatest      = ivy"org.scalatest::scalatest:${version.scalatest}"
    val scalatestPlus  = ivy"org.scalatestplus::scalatestplus-scalacheck:${version.scalatestPlus}"
  }
}

trait CommonModule extends CrossSbtModule with PublishModule {
  def publishVersion = "0.13.3-SNAPSHOT"

  def pomSettings = PomSettings(
    description = "Google Cloud Pub/Sub stream-based client built on top of cats-effect, fs2 and http4s.",
    organization = "com.permutive",
    url = "https://github.com/permutive/fs2-google-pubsub",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("permutive", "fs2-google-pubsub"),
    developers = Seq(
      Developer("cremboc", "Paulius Imbrasas", "https://github.com/cremboc"),
      Developer("TimWSpence", "Tim Spence", "https://github.com/TimWSpence"),
      Developer("bastewart", "Ben Stewart", "https://github.com/bastewart"),
    )
  )

  def commonDependencies = Agg(
    Dependencies.libraries.catsCore,
    Dependencies.libraries.effect,
    Dependencies.libraries.fs2,
  )

  def httpDependencies = Agg(
    Dependencies.libraries.alleyCatsCore,
    Dependencies.libraries.http4sDsl,
    Dependencies.libraries.http4sClient,
    Dependencies.libraries.log4cats,
    Dependencies.libraries.jwt,
    Dependencies.libraries.jsoniterCore,
  )

  def grpcDependencies = Agg(
    Dependencies.libraries.gcp,
  )

  def httpCompileDependencies = Agg(
    Dependencies.libraries.jsoniterMacros,
  )

  override def scalacOptions = List(
    "-deprecation",                      // Emit warning and location for usages of deprecated APIs.
    "-encoding", "utf-8",                // Specify character encoding used by source files.
    "-explaintypes",                     // Explain type errors in more detail.
    "-feature",                          // Emit warning and location for usages of features that should be imported explicitly.
    "-language:existentials",            // Existential types (besides wildcard types) can be written and inferred
    "-language:experimental.macros",     // Allow macro definition (besides implementation and application)
    "-language:higherKinds",             // Allow higher-kinded types
    "-language:implicitConversions",     // Allow definition of implicit functions called views
    "-unchecked",                        // Enable additional warnings where generated code depends on assumptions.
    "-Xcheckinit",                       // Wrap field accessors to throw an exception on uninitialized access.
    "-Xlint:adapted-args",               // Warn if an argument list is modified to match the receiver.
    "-Xlint:constant",                   // Evaluation of a constant arithmetic expression results in an error.
    "-Xlint:delayedinit-select",         // Selecting member of DelayedInit.
    "-Xlint:doc-detached",               // A Scaladoc comment appears to be detached from its element.
    "-Xlint:inaccessible",               // Warn about inaccessible types in method signatures.
    "-Xlint:infer-any",                  // Warn when a type argument is inferred to be `Any`.
    "-Xlint:missing-interpolator",       // A string literal appears to be missing an interpolator id.
    "-Xlint:nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Xlint:option-implicit",            // Option.apply used implicit view.
    "-Xlint:package-object-classes",     // Class or object defined in package object.
    "-Xlint:poly-implicit-overload",     // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow",             // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align",                // Pattern sequence wildcard must align with sequence component.
    "-Ywarn-extra-implicit",             // Warn when more than one implicit parameter section is defined.
    "-Ywarn-unused:imports",             // Warn if an import is unused.
    "-Ywarn-unused:patvars"              // Warn if a variable bound in a pattern is unused.
  ) ++ (
    if (scalaVersion().startsWith("2.12")) List(
      "-Xfuture",                          // Turn on future language features.
      "-Xlint:by-name-right-associative",  // By-name parameter of right associative operator.
      "-Xlint:unsound-match",              // Pattern match may not be typesafe.
      "-Yno-adapted-args",                 // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver.
      "-Ypartial-unification",             // Enable partial unification in type constructor inference
      "-Ywarn-inaccessible",               // Warn about inaccessible types in method signatures.
      "-Ywarn-infer-any",                  // Warn when a type argument is inferred to be `Any`.
      "-Ywarn-nullary-override"            // Warn when non-nullary `def f()' overrides nullary `def f'.
    ) else Nil
  )
}

object `fs2-google-pubsub` extends Cross[`fs2-google-pubsub`](Dependencies.version.cross:_*)
class `fs2-google-pubsub`(val crossScalaVersion: String) extends CommonModule {
  override def ivyDeps = commonDependencies
}

object `fs2-google-pubsub-http` extends Cross[`fs2-google-pubsub-http`](Dependencies.version.cross:_*)
class `fs2-google-pubsub-http`(val crossScalaVersion: String) extends CommonModule {
  override def moduleDeps = List(`fs2-google-pubsub`(crossScalaVersion))
  override def ivyDeps = commonDependencies ++ httpDependencies
  override def compileIvyDeps = httpCompileDependencies

  object test extends Tests {
    override def ivyDeps = Agg(
      Dependencies.libraries.scalatest,
      Dependencies.libraries.scalatestPlus,
      Dependencies.libraries.http4sHttp,
      Dependencies.libraries.log4catsSlf4j,
    )

    override def compileIvyDeps = Agg(
      Dependencies.libraries.jsoniterMacros,
    )

    override def testFrameworks = Seq("org.scalatest.tools.Framework")
  }
}

object `fs2-google-pubsub-grpc` extends Cross[`fs2-google-pubsub-grpc`](Dependencies.version.cross:_*)
class `fs2-google-pubsub-grpc`(val crossScalaVersion: String) extends CommonModule {
  override def moduleDeps = List(`fs2-google-pubsub`(crossScalaVersion))
  override def ivyDeps = commonDependencies ++ grpcDependencies

  object test extends Tests {
    override def ivyDeps = Agg(
      Dependencies.libraries.scalatest,
      Dependencies.libraries.scalatestPlus,
      Dependencies.libraries.http4sHttp,
      Dependencies.libraries.log4catsSlf4j,
    )

    override def compileIvyDeps = Agg(
      Dependencies.libraries.jsoniterMacros,
    )

    override def testFrameworks = Seq("org.scalatest.tools.Framework")
  }
}
