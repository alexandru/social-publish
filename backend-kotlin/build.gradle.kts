import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
    id("com.github.ben-manes.versions") version "0.53.0"
}

group = "com.alexn.socialpublish"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.3.3"
val arrowVersion = "2.2.1.1"
val jdbiVersion = "3.51.0"
val logbackVersion = "1.5.24"
val cliktVersion = "5.0.3"
val scrimageVersion = "4.3.5"

dependencies {
    // Kotlin stdlib
    implementation(kotlin("stdlib"))

    // Arrow for functional programming, typed errors, and resource safety
    implementation("io.arrow-kt:arrow-core:$arrowVersion")
    implementation("io.arrow-kt:arrow-fx-coroutines:$arrowVersion")
    implementation("io.arrow-kt:suspendapp-jvm:$arrowVersion")

    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // Ktor client for API calls
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // JDBI for database access
    implementation("org.jdbi:jdbi3-core:$jdbiVersion")
    implementation("org.jdbi:jdbi3-kotlin:$jdbiVersion")
    implementation("org.jdbi:jdbi3-kotlin-sqlobject:$jdbiVersion")
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")

    // Clikt for CLI parsing
    implementation("com.github.ajalt.clikt:clikt:$cliktVersion")

    // Scrimage for image processing
    implementation("com.sksamuel.scrimage:scrimage-core:$scrimageVersion")
    implementation("com.sksamuel.scrimage:scrimage-webp:$scrimageVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.14")

    // RSS generation
    implementation("com.rometools:rome:2.1.0")

    // OAuth for Twitter
    implementation("oauth.signpost:signpost-core:2.1.1")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
    jvmToolchain(17)
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

