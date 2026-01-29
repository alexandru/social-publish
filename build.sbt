import sbtnativeimage.NativeImagePlugin.autoImport._

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

lazy val backendSharedSettings = Seq(
  version := "1.0.0",
  scalaVersion := scala3Version,
  Compile / mainClass := Some("socialpublish.Main")
)

// Backend project definition, located in the backend/ folder
lazy val backend = (project in file("backend"))
  .settings(backendSharedSettings)
  .settings(
    name := "social-publish-backend",
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
      "org.apache.tika" % "tika-core" % "2.9.2",

      // BCrypt for password hashing/verification
      "org.mindrot" % "jbcrypt" % "0.4",

      // Testing
      "org.scalameta" %% "munit" % "1.0.3" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.1.0" % Test,
      "org.tpolecat" %% "doobie-munit" % doobieVersion % Test,
      "org.graalvm.buildtools" % "graalvm-reachability-metadata" % "0.10.6" % Runtime
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

lazy val backendNative = (project in file(".backend-native"))
  .dependsOn(backend)
  .aggregate(backend)
  .enablePlugins(NativeImagePlugin)
  .settings(backendSharedSettings)
  .settings(
    nativeImageVersion := "21.0.2",
    nativeImageJvm := "graalvm-java21",
    nativeImageOutput := baseDirectory.value / "target" / "native-image" / "social-publish-backend",
    nativeImageOptions ++= Seq(
      "--enable-https",
      "--no-fallback",
      "--report-unsupported-elements-at-runtime"
    ),
    Global / excludeLintKeys ++= Set(nativeImageVersion, nativeImageJvm),
    // GraalVM Reachability Metadata
    Compile / resourceGenerators += Def.task {
      import NativeImageGenerateMetadataFiles._
      implicit val logger: sbt.util.Logger = sbt.Keys.streams.value.log
      generateResourceFiles(
        // Path needed for cloning the metadata repository
        (Compile / target).value,
        // Path where the metadata files will be generated
        (Compile / resourceManaged).value / "META-INF" / "native-image",
        // List all tranzitive dependencies (can also add our own files)
        update.value
          .allModules
          .map(m => Artefact(s"${m.organization}:${m.name}:${m.revision}"))
          .toList ++ List(
          ProjectResourceConfigFile("my-resource-config.json")
        )
      )
    }.taskValue
  )

// optional root aggregator: makes running `sbt` useful at the repository root
lazy val root = (project in file("."))
  .aggregate(backend)
  .aggregate(backendNative)
  .settings(
    name := "social-publish",
    ThisBuild / scalaVersion := scala3Version
  )

Global / onChangedBuildSource := ReloadOnSourceChanges
