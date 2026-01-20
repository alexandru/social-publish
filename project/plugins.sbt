addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.2")
addSbtPlugin("org.scalameta" % "sbt-native-image" % "0.3.4")

// ScalaJS plugins
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.19.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.21.1")
addSbtPlugin("org.scalablytyped.converter" % "sbt-converter" % "1.0.0-beta44")

libraryDependencies ++= Seq(
  "io.circe" %% "circe-parser" % "0.14.15",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "7.5.0.202512021534-r"
)
