import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.kotlinSerialization) // Kotlin Serialization Plugin
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// The IntelliJ Platform supplies the Kotlin stdlib, kotlinx-coroutines and slf4j on the core
// classloader. A plugin that bundles its own ends up with two copies of the coroutines internals
// on one classloader, which fails at runtime in ways no test reproduces. Declared once here
// rather than per-dependency, so a new dependency cannot quietly reintroduce them.
// https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#coroutines-library
listOf(configurations.implementation, configurations.testImplementation).forEach { config ->
    config.configure {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    // MCP Kotlin SDK. Split artifacts only — see the note in libs.versions.toml on why the
    // `kotlin-sdk-jvm` monolith is not used.
    implementation(libs.mcp.kotlin.sdk.core)
    implementation(libs.mcp.kotlin.sdk.server)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Ktor Server (for the embedded MCP server with configurable port)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
        // Java-plugin test support (JavaCodeInsightFixtureTestCase, IdeaTestUtil, …) — required by
        // the live-debuggee suite under src/test/…/livedebug, which needs a heavy Java project
        // fixture so the debugger's PositionManager can map JVM classes back to source files.
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        // The label is normalised so that `-rc1` and `-rc.1` both land on the `rc` channel (the raw
        // substring form created a brand-new Marketplace channel per RC), and an unknown label fails
        // the build instead of silently minting a channel.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            val label = version.substringAfter('-', "").substringBefore('.').trimEnd { it.isDigit() }
            listOf(
                when (label) {
                    "" -> "default"
                    "alpha", "beta", "rc", "eap" -> label
                    else -> throw GradleException(
                        "Unrecognised pre-release label '$label' in pluginVersion '$version'. " +
                            "Use alpha / beta / rc / eap, or no label for the stable channel."
                    )
                }
            )
        }
    }

    pluginVerification {
        ides {
            // CI drives per-IDE verification legs with -PverifyIdeType=IC|PY|WS|GO (see build.yml's
            // `verify` matrix); local runs without the property keep recommended() (IC only, because
            // recommended() derives its product type solely from `platformType`).
            val requested = providers.gradleProperty("verifyIdeType").orNull
            if (requested.isNullOrBlank()) {
                recommended()
            } else {
                select {
                    types = requested.split(',').map { IntelliJPlatformType.fromCode(it.trim()) }
                    channels = listOf(ProductRelease.Channel.RELEASE)
                    sinceBuild = providers.gradleProperty("pluginSinceBuild")
                    untilBuild = "999.*"
                }
            }
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        filters {
            excludes {
                // Swing glue with no headless fixture — excluded so the coverage number measures
                // the logic surface instead of being diluted by UI code nothing can exercise.
                packages(
                    "com.github.hechtcarmel.jetbrainsdebuggermcpplugin.ui",
                    "com.github.hechtcarmel.jetbrainsdebuggermcpplugin.actions",
                    "com.github.hechtcarmel.jetbrainsdebuggermcpplugin.icons",
                )
            }
        }
        total {
            xml {
                onCheck = true
            }
            verify {
                // A ratchet, not a target: set just below current coverage (64.8% line,
                // post-exclusion, 2026-08-02) so it passes today and fails on a real
                // regression. Raise it as coverage grows.
                rule("Line coverage of the logic surface must not regress") {
                    bound {
                        minValue = 62
                        coverageUnits = CoverageUnit.LINE
                    }
                }
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    test {
        // A silently-skipped test is indistinguishable from a passing one in the console.
        testLogging {
            events("skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }

        // Gradle's -D lands on the daemon, not the forked test JVM, so the golden-file
        // regeneration flag has to be forwarded explicitly.
        providers.systemProperty("contract.update").orNull?.let {
            systemProperty("contract.update", it)
        }
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    // verifyPluginProjectConfiguration catches sinceBuild/plugin.xml/toolchain mismatches in
    // seconds; without this wiring it runs in no CI task graph at all.
    check {
        dependsOn(named("verifyPluginProjectConfiguration"))
    }

//    runIde {
//        jvmArgs("-Xmx20g", "-Xms1g")
//    }


}
