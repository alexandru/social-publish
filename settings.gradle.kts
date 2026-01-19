rootProject.name = "social-publish-multiproject"

include("backend-kotlin")
include("frontend-kotlin")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            // Versions
            version("kotlin", "2.3.0")
            version("ktor", "3.3.3")
            version("arrow", "2.2.1.1")
            version("jdbi", "3.51.0")
            version("logback", "1.5.24")
            version("clikt", "5.0.3")
            version("scrimage", "4.3.5")
            version("kotlinx-serialization", "1.9.0")
            version("sqlite-jdbc", "3.51.1.0")
            version("kotlin-logging", "7.0.14")
            version("rome", "2.1.0")
            version("scribejava", "8.3.3")
            version("mockk", "1.14.7")
            version("kotlinx-coroutines", "1.10.2")

            // Kotlin libraries
            library("kotlin-stdlib", "org.jetbrains.kotlin", "kotlin-stdlib").withoutVersion()

            // Arrow libraries
            library("arrow-core", "io.arrow-kt", "arrow-core").versionRef("arrow")
            library("arrow-fx-coroutines", "io.arrow-kt", "arrow-fx-coroutines").versionRef("arrow")
            library("arrow-suspendapp", "io.arrow-kt", "suspendapp-jvm").versionRef("arrow")
            library("arrow-suspendapp-ktor", "io.arrow-kt", "suspendapp-ktor-jvm").versionRef("arrow")

            // Ktor server libraries
            library("ktor-server-core", "io.ktor", "ktor-server-core").versionRef("ktor")
            library("ktor-server-cio", "io.ktor", "ktor-server-cio").versionRef("ktor")
            library("ktor-server-content-negotiation", "io.ktor", "ktor-server-content-negotiation").versionRef("ktor")
            library("ktor-serialization-kotlinx-json", "io.ktor", "ktor-serialization-kotlinx-json").versionRef("ktor")
            library("ktor-server-auth", "io.ktor", "ktor-server-auth").versionRef("ktor")
            library("ktor-server-auth-jwt", "io.ktor", "ktor-server-auth-jwt").versionRef("ktor")
            library("ktor-server-status-pages", "io.ktor", "ktor-server-status-pages").versionRef("ktor")
            library("ktor-server-call-logging", "io.ktor", "ktor-server-call-logging").versionRef("ktor")
            library("ktor-server-cors", "io.ktor", "ktor-server-cors").versionRef("ktor")

            // Ktor client libraries
            library("ktor-client-core", "io.ktor", "ktor-client-core").versionRef("ktor")
            library("ktor-client-cio", "io.ktor", "ktor-client-cio").versionRef("ktor")
            library("ktor-client-content-negotiation", "io.ktor", "ktor-client-content-negotiation").versionRef("ktor")
            library("ktor-client-logging", "io.ktor", "ktor-client-logging").versionRef("ktor")
            library("ktor-client-auth", "io.ktor", "ktor-client-auth").versionRef("ktor")

            // Kotlinx Serialization
            library("kotlinx-serialization-json", "org.jetbrains.kotlinx", "kotlinx-serialization-json").versionRef("kotlinx-serialization")

            // Kotlinx Coroutines
            library("kotlinx-coroutines-core", "org.jetbrains.kotlinx", "kotlinx-coroutines-core").versionRef("kotlinx-coroutines")

            // JDBI libraries
            library("jdbi-core", "org.jdbi", "jdbi3-core").versionRef("jdbi")
            library("jdbi-kotlin", "org.jdbi", "jdbi3-kotlin").versionRef("jdbi")
            library("jdbi-kotlin-sqlobject", "org.jdbi", "jdbi3-kotlin-sqlobject").versionRef("jdbi")
            library("sqlite-jdbc", "org.xerial", "sqlite-jdbc").versionRef("sqlite-jdbc")

            // Clikt for CLI
            library("clikt", "com.github.ajalt.clikt", "clikt").versionRef("clikt")

            // Scrimage libraries
            library("scrimage-core", "com.sksamuel.scrimage", "scrimage-core").versionRef("scrimage")
            library("scrimage-webp", "com.sksamuel.scrimage", "scrimage-webp").versionRef("scrimage")

            // Logging
            library("logback-classic", "ch.qos.logback", "logback-classic").versionRef("logback")
            library("kotlin-logging", "io.github.oshai", "kotlin-logging-jvm").versionRef("kotlin-logging")

            // RSS generation
            library("rome", "com.rometools", "rome").versionRef("rome")

            // OAuth
            library("scribejava-core", "com.github.scribejava", "scribejava-core").versionRef("scribejava")

            // Testing libraries
            library("kotlin-test", "org.jetbrains.kotlin", "kotlin-test").withoutVersion()
            library("ktor-server-test-host", "io.ktor", "ktor-server-test-host").versionRef("ktor")
            library("ktor-client-mock", "io.ktor", "ktor-client-mock").versionRef("ktor")
            library("mockk", "io.mockk", "mockk").versionRef("mockk")
            library("kotlinx-coroutines-test", "org.jetbrains.kotlinx", "kotlinx-coroutines-test").versionRef("kotlinx-coroutines")

            // Bundles for grouped dependencies
            bundle(
                "arrow",
                listOf(
                    "arrow-core",
                    "arrow-fx-coroutines",
                    "arrow-suspendapp",
                    "arrow-suspendapp-ktor",
                )
            )

            bundle(
                "ktor-server",
                listOf(
                    "ktor-server-core",
                    "ktor-server-cio",
                    "ktor-server-content-negotiation",
                    "ktor-serialization-kotlinx-json",
                    "ktor-server-auth",
                    "ktor-server-auth-jwt",
                    "ktor-server-status-pages",
                    "ktor-server-call-logging",
                    "ktor-server-cors",
                ),
            )
            bundle(
                "ktor-client",
                listOf(
                    "ktor-client-core",
                    "ktor-client-cio",
                    "ktor-client-content-negotiation",
                    "ktor-client-logging",
                    "ktor-client-auth",
                ),
            )
            bundle("jdbi", listOf("jdbi-core", "jdbi-kotlin", "jdbi-kotlin-sqlobject"))
            bundle("scrimage", listOf("scrimage-core", "scrimage-webp"))
            bundle("logging", listOf("logback-classic", "kotlin-logging"))
        }
    }
}
