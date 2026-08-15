import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

intellijPlatform {
    pluginVerification {
        ides {
            recommended()
        }
    }
    signing {
        certificateChain = providers.gradleProperty("intellijPlatform.signing.certificateChain")
        privateKey = providers.gradleProperty("intellijPlatform.signing.privateKey")
        password = providers.gradleProperty("intellijPlatform.signing.password")
    }
    publishing {
        token = providers.gradleProperty("intellijPlatform.publishing.token")
        channels = listOf("default")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
    }
}
