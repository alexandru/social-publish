import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.graalvm.buildtools.native") version "0.11.4"
}

group = "com.alexn.socialpublish"

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

    // Scrimage for image processing
    implementation(libs.bundles.scrimage)

    // Logging
    implementation(libs.bundles.logging)

    // RSS generation
    implementation(libs.rome)

    // OAuth for Twitter
    implementation(libs.scribejava.core)

    // HTML parsing
    implementation(libs.jsoup)

    // BCrypt for password hashing
    implementation(libs.jbcrypt)

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

application { mainClass.set("com.alexn.socialpublish.MainKt") }

graalvmNative {
    metadataRepository {
        enabled = true
        version = "0.3.8"
    }

    binaries {
        named("main") {
            fallback.set(false)
            verbose.set(true)
            imageName.set("social-publish")

            buildArgs.add(
                "--initialize-at-build-time=io.ktor,kotlinx,kotlin,org.xml.sax.helpers,org.slf4j.helpers"
            )
            buildArgs.add(
                "--initialize-at-build-time=org.slf4j.LoggerFactory,ch.qos.logback,org.slf4j.impl.StaticLoggerBinder"
            )
            buildArgs.add(
                "--initialize-at-build-time=com.github.ajalt.mordant.internal.nativeimage.NativeImagePosixMppImpls"
            )
            buildArgs.add("--initialize-at-build-time=ch.qos.logback.classic.Logger")

            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+InstallExitHandlers")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("-H:+ReportUnsupportedElementsAtRuntime")
        }
    }
}

tasks {
    jar {
        manifest { attributes("Main-Class" to "com.alexn.socialpublish.MainKt") }
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // Fat JAR task
    register<Jar>("fatJar") {
        archiveClassifier.set("all")
        manifest { attributes("Main-Class" to "com.alexn.socialpublish.MainKt") }
        from(sourceSets.main.get().output)
        dependsOn(configurations.runtimeClasspath)
        from({
            configurations.runtimeClasspath
                .get()
                .filter { it.exists() }
                .map { if (it.isDirectory) it else zipTree(it) }
        })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
