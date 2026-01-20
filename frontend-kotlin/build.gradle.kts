import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.ncorti.ktfmt.gradle") version "0.21.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

kotlin {
    js(IR) {
        browser {
            binaries.executable()

            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
                outputFileName = "app.js"
            }

            runTask {
                val resourcesDir = project.layout.buildDirectory.dir("processedResources/js/main").get().asFile
                devServerProperty.set(
                    KotlinWebpackConfig.DevServer(
                        port = 3001,
                        proxy = mutableListOf(
                            KotlinWebpackConfig.DevServer.Proxy(
                                context = mutableListOf("/api"),
                                target = "http://localhost:3000",
                            ),
                            KotlinWebpackConfig.DevServer.Proxy(
                                context = mutableListOf("/rss"),
                                target = "http://localhost:3000",
                            ),
                            KotlinWebpackConfig.DevServer.Proxy(
                                context = mutableListOf("/files"),
                                target = "http://localhost:3000",
                            ),
                        ),
                    ).apply {
                        static(resourcesDir.path, watch = true)
                    },
                )
            }

        }
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react:2026.1.5-19.2.3")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom:2026.1.5-19.2.3")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-tanstack-react-router:2026.1.5-1.147.1")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-browser:2026.1.5")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-web:2026.1.5")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-js:2026.1.5")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(npm("bulma", "1.0.4"))
                implementation(npm("ionicons", "8.0.13"))
            }
        }
    }

    sourceSets.all {
        languageSettings {
            progressiveMode = true
        }
    }
}

ktfmt {
    googleStyle()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    autoCorrect = false
    ignoreFailures = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(false)
        xml.required.set(false)
        txt.required.set(true)
        sarif.required.set(false)
    }
}

tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask>().configureEach {
    enabled = true
}

tasks.named("check") {
    dependsOn(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>())
    dependsOn(tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask>())
}
