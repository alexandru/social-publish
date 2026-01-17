import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
    id("com.github.ben-manes.versions") version "0.53.0"
}

group = "com.alexn.socialpublish"
version = "1.0.0"

repositories {
    mavenCentral()
}

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

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    version.set("1.2.1")
    android.set(false)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/build/**")
        exclude("**/resources/**")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
    }
}

application {
    mainClass.set("com.alexn.socialpublish.MainKt")
}

tasks {
    jar {
        manifest {
            attributes(
                "Main-Class" to "com.alexn.socialpublish.MainKt",
            )
        }
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // Fat JAR task
    register<Jar>("fatJar") {
        archiveClassifier.set("all")
        manifest {
            attributes("Main-Class" to "com.alexn.socialpublish.MainKt")
        }
        from(sourceSets.main.get().output)
        dependsOn(configurations.runtimeClasspath)
        from({ configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) } })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    named<DependencyUpdatesTask>("dependencyUpdates").configure {
        fun isNonStable(version: String): Boolean {
            val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
            val regex = "^[0-9,.v-]+(-r)?$".toRegex()
            val isStable = stableKeyword || regex.matches(version)
            return isStable.not()
        }

        rejectVersionIf {
            isNonStable(candidate.version) && !isNonStable(currentVersion)
        }
        checkForGradleUpdate = true
        outputFormatter = "html"
        outputDir = "build/dependencyUpdates"
        reportfileName = "report"
    }
}
