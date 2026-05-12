plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "dev.pi"
version = "0.2.3"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

tasks.processResources {
    from("../pi-extension") {
        into("pi-extension")
    }
}

intellijPlatform {
    projectName = "pi-intellij-diff"
    buildSearchableOptions = false

    pluginConfiguration {
        id = "dev.pi.intellij-diff"
        name = "Pi Coding Agent Diff Approval"
        version = project.version.toString()
        description = "Opens IntelliJ diff approval dialogs for Pi Coding Agent file edits and writes."

        vendor {
            name = "Pi"
            email = "support@pi.dev"
            url = "https://pi.dev"
        }

        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
