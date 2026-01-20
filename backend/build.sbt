val scala3Version = "3.7.4"
val circeVersion = "0.14.15"
val doobieVersion = "1.0.0-RC11"
val catsEffectVersion = "3.6.3"
val declineVersion = "2.5.0"
val log4catsVersion = "2.7.1"
val tapirVersion = "1.13.5"
val sttpVersion = "4.0.13"
val http4sVersion = "0.23.27"
val logbackClassicVersion = "1.5.16"

lazy val root = project
  .in(file("."))
  .settings(
    name := "social-publish-backend",
    version := "1.0.0",
    scalaVersion := scala3Version,
    scalacOptions ++= Seq(
      "-no-indent",
      "-rewrite"
    ),
    libraryDependencies ++= Seq(
      // Cats Effect for IO
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "cats-mtl" % "1.6.0",

      // Tapir + Http4s server + OpenAPI
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-client4" % tapirVersion,

      // Http4s Server
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,

      // sttp client (cats-effect backend)
      "com.softwaremill.sttp.client4" %% "cats" % sttpVersion,

      // Circe for JSON
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-literal" % circeVersion,

      // Doobie for database and HikariCP
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,

      // CLI parsing
      "com.monovore" %% "decline-effect" % declineVersion,

      // Logging
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % logbackClassicVersion,

      // Java libraries for utilities
      "com.github.jwt-scala" %% "jwt-circe" % "11.0.3",
      "org.xerial" % "sqlite-jdbc" % "3.51.1.0",
      "com.drewnoakes" % "metadata-extractor" % "2.19.0",
      "com.twelvemonkeys.imageio" % "imageio-core" % "3.13.0",
      "com.twelvemonkeys.imageio" % "imageio-jpeg" % "3.13.0",

      // Testing
      "org.scalameta" %% "munit" % "1.0.3" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.1.0" % Test,
      "org.tpolecat" %% "doobie-munit" % doobieVersion % Test
    ),

    // Assembly settings for building a fat JAR
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "maven", "org.webjars", "swagger-ui", "pom.properties") =>
        MergeStrategy.singleOrError
      case PathList("META-INF", "resources", "webjars", "swagger-ui", xs @ _*) =>
        MergeStrategy.singleOrError
      case PathList("META-INF", "versions", xs @ _*) => MergeStrategy.first
      case PathList("META-INF", "maven", xs @ _*) => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) =>
        MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".DSA")) =>
        MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".RSA")) =>
        MergeStrategy.discard
      case PathList("module-info.class") => MergeStrategy.discard
      case "application.conf" => MergeStrategy.concat
      case "reference.conf" => MergeStrategy.concat
      case x if x.endsWith("/module-info.class") => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    assembly / mainClass := Some("socialpublish.Main"),
    assembly / assemblyJarName := "social-publish-backend.jar"
  )

Global / onChangedBuildSource := ReloadOnSourceChanges
