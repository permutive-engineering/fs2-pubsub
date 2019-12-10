def priorTo2_13(scalaVersion: String): Boolean =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, minor)) if minor < 13 => true
    case _                              => false
  }

lazy val commonSettings = Seq(
  scalaVersion := Dependencies.Versions.scala212,
  crossScalaVersions := Seq(Dependencies.Versions.scala212, Dependencies.Versions.scala213),
  javacOptions in (Compile, compile) ++= Seq("-source", "1.8", "-target", "1.8"),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.11.0").cross(CrossVersion.full)),
  scalacOptions ++= Seq(
    "-deprecation", // Emit warning and location for usages of deprecated APIs.
    "-feature",     // Emit warning and location for usages of features that should be imported explicitly.
    "-encoding",
    "utf-8",                         // Specify character encoding used by source files.
    "-explaintypes",                 // Explain type errors in more detail.
    "-language:existentials",        // Existential types (besides wildcard types) can be written and inferred
    "-language:experimental.macros", // Allow macro definition (besides implementation and application)
    "-language:higherKinds",         // Allow higher-kinded types
    "-language:implicitConversions", // Allow definition of implicit functions called views
    "-unchecked",                    // Enable additional warnings where generated code depends on assumptions.
    "-Xlint:infer-any",              // Warn when a type argument is inferred to be `Any`.
    "-Ywarn-value-discard",
    "-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
    //  "-Xfatal-warnings",                  // Fail the compilation if there are any warnings.
    "-Xlint:adapted-args",           // Warn if an argument list is modified to match the receiver.
    "-Xlint:delayedinit-select",     // Selecting member of DelayedInit.
    "-Xlint:doc-detached",           // A Scaladoc comment appears to be detached from its element.
    "-Xlint:inaccessible",           // Warn about inaccessible types in method signatures.
    "-Xlint:infer-any",              // Warn when a type argument is inferred to be `Any`.
    "-Xlint:missing-interpolator",   // A string literal appears to be missing an interpolator id.
    "-Xlint:nullary-override",       // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Xlint:nullary-unit",           // Warn when nullary methods return Unit.
    "-Xlint:option-implicit",        // Option.apply used implicit view.
    "-Xlint:package-object-classes", // Class or object defined in package object.
    "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow",         // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align",            // Pattern sequence wildcard must align with sequence component.
    "-Xlint:type-parameter-shadow",  // A local type parameter shadows a type already in scope.
    "-Ywarn-dead-code",              // Warn when dead code is identified.
    "-Ywarn-numeric-widen",          // Warn when numerics are widened.
    "-Ywarn-value-discard",          // Warn when non-Unit expression results are unused.
    // Scala 2.12 below
    "-Xlint:constant",         // Evaluation of a constant arithmetic expression results in an error.
    "-Ywarn-extra-implicit",   // Warn when more than one implicit parameter section is defined.
    "-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports",   // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals",    // Warn if a local definition is unused.
    "-Ywarn-unused:params",    // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars",   // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates",  // Warn if a private member is unused.

    // Inlining
    "-opt:l:inline",
    "-opt:l:method",
    "-opt-inline-from:com.permutive.**",
    "-opt-warnings",
    // Lint after expansion so that implicits used in macros are not flagged as unused
    "-Ywarn-macros:after",
  ),
  scalacOptions ++= {
    if (priorTo2_13(scalaVersion.value))
      Seq(
        "-Yno-adapted-args",                // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver.
        "-Ypartial-unification",            // Enable partial unification in type constructor inference
        "-Ywarn-inaccessible",              // Warn about inaccessible types in method signatures.
        "-Ywarn-infer-any",                 // Warn when a type argument is inferred to be `Any`.
        "-Ywarn-nullary-override",          // Warn when non-nullary `def f()' overrides nullary `def f'.
        "-Ywarn-nullary-unit",              // Warn when nullary methods return Unit.
        "-Xlint:by-name-right-associative", // By-name parameter of right associative operator.
        "-Xlint:unsound-match",             // Pattern match may not be typesafe.
        "-Xfuture",                         // Turn on future language features.
      )
    else
      Seq(
        "-Ymacro-annotations",
      )
  },
  libraryDependencies ++= {
    if (priorTo2_13(scalaVersion.value)) {
      Seq(compilerPlugin(("org.scalamacros" % "paradise" % "2.1.1").cross(CrossVersion.patch)))
    } else Nil
  },
)

lazy val common = (project in file("fs2-google-pubsub"))
  .settings(
    name := "fs2-google-pubsub",
    commonSettings,
    libraryDependencies ++= Dependencies.commonDependencies,
    libraryDependencies ++= Dependencies.testsDependencies,
  )

lazy val http = (project in file("fs2-google-pubsub-http"))
  .dependsOn(common)
  .settings(
    name := "fs2-google-pubsub-http",
    commonSettings,
    libraryDependencies ++= Dependencies.httpDependencies,
    libraryDependencies ++= Dependencies.testsDependencies,
  )

lazy val grpc = (project in file("fs2-google-pubsub-grpc"))
  .dependsOn(common)
  .settings(
    name := "fs2-google-pubsub-grpc",
    commonSettings,
    libraryDependencies ++= Dependencies.grpcDependencies,
    libraryDependencies ++= Dependencies.testsDependencies,
  )

lazy val root = (project in file("."))
  .settings(
    name := "fs2-google-pubsub",
    scalaVersion := Dependencies.Versions.scala212,
    publish / skip := true,
  )
  .aggregate(
    common,
    http,
    grpc
  )
