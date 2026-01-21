import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

kotlin {
    js(IR) {
        browser {
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
                        port = 3002,
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
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(compose.html.core)
                implementation(compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(npm("bulma", "1.0.4"))
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
