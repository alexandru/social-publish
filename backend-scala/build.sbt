val scala3Version = "3.3.4"

val http4sVersion = "0.23.30"
val circeVersion = "0.14.10"
val doobieVersion = "1.0.0-RC5"
val catsEffectVersion = "3.5.7"
val declineVersion = "2.4.1"
val log4catsVersion = "2.7.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "social-publish-backend",
    version := "1.0.0",
    scalaVersion := scala3Version,
    
    libraryDependencies ++= Seq(
      // Cats Effect for IO
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "cats-mtl" % "1.5.0",
      
      // Http4s for HTTP server and client
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      
      // Circe for JSON
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-literal" % circeVersion,
      
      // Doobie for database
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-sqlite" % doobieVersion,
      
      // CLI parsing
      "com.monovore" %% "decline-effect" % declineVersion,
      
      // Logging
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % "1.5.16",
      
      // Java libraries for utilities
      "com.github.jwt-scala" %% "jwt-circe" % "10.0.1",
      "org.xerial" % "sqlite-jdbc" % "3.48.0.0",
      "com.drewnoakes" % "metadata-extractor" % "2.19.0",
      "com.twelvemonkeys.imageio" % "imageio-core" % "3.12.0",
      "com.twelvemonkeys.imageio" % "imageio-jpeg" % "3.12.0",
      
      // Testing
      "org.scalameta" %% "munit" % "1.0.3" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
      "org.tpolecat" %% "doobie-munit" % doobieVersion % Test
    ),
    
    // Assembly settings for building a fat JAR
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "versions", xs @ _*) => MergeStrategy.first
      case PathList("META-INF", "maven", xs @ _*) => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".DSA")) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".RSA")) => MergeStrategy.discard
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
