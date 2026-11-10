import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The plugin takes no version: it is bundled with the Gradle wrapper and compiles with Gradle's
// embedded Kotlin.
plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
