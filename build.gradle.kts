plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}


group = "com.alenkaleb.jakartadoctor"
version = "2026.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // Target 2024.3+ (existe 2024.3.x; ajuste se quiser) :contentReference[oaicite:5]{index=5}
        intellijIdeaCommunity("2024.3.7")

        // Precisamos de Java + Kotlin no ambiente de desenvolvimento
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
}

kotlin {
    jvmToolchain(21)
}
