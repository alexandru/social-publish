plugins {
    kotlin("multiplatform") version "2.3.0" apply false
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
    id("com.github.ben-manes.versions") version "0.53.0" apply false
    id("com.ncorti.ktfmt.gradle") version "0.21.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-wrappers/maven")
    }
}

subprojects {
    apply(plugin = "com.github.ben-manes.versions")
    apply(plugin = "com.ncorti.ktfmt.gradle")

    extensions.configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
        kotlinLangStyle()
    }

    tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
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
