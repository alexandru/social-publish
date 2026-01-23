import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.graalvm.buildtools.native") version "0.11.4"
}

group = "socialpublish.backend"

version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    // Kotlin stdlib
    implementation(libs.kotlin.stdlib)

    // Arrow for functional programming, typed errors, and resource safety
    implementation(libs.bundles.arrow)

    // Ktor server
    implementation(libs.bundles.ktor.server)

    // Ktor client for API calls
    implementation(libs.bundles.ktor.client)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // JDBI for database access
    implementation(libs.bundles.jdbi)
    implementation(libs.sqlite.jdbc)

    // Clikt for CLI parsing
    implementation(libs.clikt)

    // Logging
    implementation(libs.bundles.logging)

    // RSS generation
    implementation(libs.rome)

    // OAuth for Twitter
    implementation(libs.scribejava.core)

    // HTML parsing
    implementation(libs.jsoup)

    // BCrypt for password hashing
    implementation(libs.bcrypt)
    implementation(libs.jbcrypt)

    // Apache Commons Text for string escaping (e.g., shell commands)
    implementation(libs.apache.commons.text)
    // Apache Tika for MIME type detection
    implementation(libs.apache.tika.core)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }

kotlin {
    // Use the default JVM from the environment/container instead of requiring a specific toolchain
}

java {
    // Ensure Java compilation target matches Kotlin `jvmTarget` to avoid inconsistent JVM target
    // errors
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        // Target JVM bytecode 21 (minimum). Do not require a specific toolchain — use the JVM
        // available in the environment.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Ensure tests run with Java 21
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

application { mainClass.set("socialpublish.backend.MainKt") }

graalvmNative {
    metadataRepository {
        enabled = true
        version = "0.3.8"
    }

    val commonBuildArgs =
        listOf(
            "--initialize-at-build-time=io.ktor,kotlinx,kotlin,org.xml.sax.helpers,org.slf4j.helpers",
            "--initialize-at-build-time=org.slf4j.LoggerFactory,ch.qos.logback,org.slf4j.impl.StaticLoggerBinder",
            "--initialize-at-build-time=com.github.ajalt.mordant.internal.nativeimage.NativeImagePosixMppImpls",
            "--initialize-at-build-time=ch.qos.logback.classic.Logger",
            "--initialize-at-run-time=io.ktor.server.cio.backend.HttpServerKt",
            "--no-fallback",
            "-H:+UnlockExperimentalVMOptions",
            "-H:+InstallExitHandlers",
            "-H:+ReportExceptionStackTraces",
            "-H:+ReportUnsupportedElementsAtRuntime",
        )

    binaries {
        named("main") {
            fallback.set(false)
            verbose.set(true)
            imageName.set("social-publish")
            buildArgs.addAll(commonBuildArgs)
        }

        named("test") {
            fallback.set(false)
            verbose.set(true)
            buildArgs.addAll(commonBuildArgs)
        }
    }
}

tasks {
    jar {
        manifest { attributes("Main-Class" to "socialpublish.backend.MainKt") }
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // Exclude signature files from signed JARs to prevent security exceptions
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
        // Exclude module-info to avoid conflicts in fat JAR
        exclude("**/module-info.class")
    }
}
