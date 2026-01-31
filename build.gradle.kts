plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.alenkaleb.jakartadoctor"
version = "2026.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target 2024.3.x (build 243.*)
        intellijIdeaCommunity("2024.3.7")

        // Para Java PSI/UAST
        bundledPlugin("com.intellij.java")

        // Você usa Kt* PSI -> precisa do Kotlin plugin no sandbox
        bundledPlugin("org.jetbrains.kotlin")

        // Para o licensing (LicensingFacade etc.)
        bundledPlugin("com.intellij.marketplace")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // IntelliJ 2024.3 = build 243.*
            sinceBuild = "243"
            untilBuild = "243.*"
        }
    }

    pluginVerification {
        ides {
            // ✅ resolve seu erro: define IDEs pra rodar o verifier
            recommended()

            // Alternativa: fixar exatamente o mesmo alvo do sandbox:
            // create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7")
        }
    }
}
