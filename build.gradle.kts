plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.aireview"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
}

kotlin {
    jvmToolchain(17)
}

intellij {
    // Target IntelliJ IDEA 2024.1 Community Edition
    version.set("2024.1.7")
    type.set("IC")
    plugins.set(listOf("Git4Idea"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        // untilBuild is intentionally not set — the plugin is compatible with all
        // future IDE versions until proven otherwise. JetBrains Marketplace rejects
        // uploads that exclude current stable builds, and a hardcoded upper bound
        // requires a re-release for every new IDE version.
    }

    buildSearchableOptions {
        enabled = false
    }

    runPluginVerifier {
        // Verify against the minimum supported build and the two most recent releases.
        // Add or remove entries here as new IDE versions are released.
        ideVersions.set(listOf(
            "IC-2024.1.7",   // minimum (sinceBuild = 241)
            "IC-2024.2.5",
            "IC-2024.3.5",
            "IC-2025.1"
        ))
        // Fail the build only on real compatibility problems, not on warnings.
        failureLevel.set(
            listOf(
                org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.INVALID_PLUGIN
            )
        )
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
        // Publish to the default (Stable) channel. Change to "EAP" for pre-releases.
        channels.set(listOf("Stable"))
    }
}
