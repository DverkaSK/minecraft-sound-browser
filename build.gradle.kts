import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        pluginVerifier()
        zipSigner()
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.intellij.groovy")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    implementation("com.googlecode.soundlibs:jorbis:0.0.17.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = provider {
            val text = file("CHANGELOG.md").readText()
            val section = text.substringAfter("\n## ").substringAfter("\n").substringBefore("\n## ").trim()
            section.lines().filter { it.isNotBlank() }.joinToString("\n", "<ul>\n", "\n</ul>") { line ->
                "<li>" + line.trimStart('-', ' ', '*')
                    .replace(Regex("`([^`]+)`"), "<code>\$1</code>")
                    .replace(Regex("""\*\*([^*]+)\*\*"""), "<b>\$1</b>") + "</li>"
            }
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
    pluginVerification {
        ides {
            val local = providers.gradleProperty("verifierIdes").orNull
            if (local != null) local.split(';').filter { it.isNotBlank() }.forEach { local(file(it)) } else recommended()
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Didea.trust.all.projects=true",
                        "-Dide.show.tips.on.startup.default.value=false",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                    )
                }
                providers.gradleProperty("runIdeProject").orNull?.let { args(listOf(it)) }
            }
            plugins {
                robotServerPlugin()
                plugin("org.intellij.scala", "2024.3.20")
            }
        }
    }
}

tasks {
    runIde {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Didea.trust.all.projects=true",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Djb.consents.confirmation.enabled=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Didea.is.internal=false",
            )
        }
        providers.gradleProperty("runIdeProject").orNull?.let { args(listOf(it)) }
    }
}
