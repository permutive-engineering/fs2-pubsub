// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "com.permutive"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// License of your choice
licenses := List("Apache 2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

// Where is the source code hosted
import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("permutive", "fs2-google-pubsub", "engineering@permutive.com"))

homepage := Some(url("https://github.com/permutive/fs2-google-pubsub"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/permutive/fs2-google-pubsub"),
    "scm:git@github.com:permutive/fs2-google-pubsub.git"
  )
)

developers := List(
  Developer(id="paulius_permutive", name="Paulius Imbrasas", email="paulius@permutive.com", url=url("https://www.permutive.com")),
)