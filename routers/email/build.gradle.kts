// Gravel first-party router app: Sends what you said as an email through an SMTP server you set up, a Proton Bridge for example
// (routers/README.md). The signing block is the same in every router app: the one keystore for
// all of them lives outside the tree, the build signs release only when the environment names
// it and packages release unsigned otherwise, so a fresh clone and CI build without it; a Gradle
// script shared between the apps would not see the Android plugin's types, so it is repeated.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeCompiler)
}

val routerKeystore: String? = System.getenv("ROUTER_KEYSTORE")?.takeIf { it.isNotBlank() }
// The router apps carry the main app's version and are released with it (androidApp/version.properties).
val routerVersion = Properties().apply { rootProject.file("androidApp/version.properties").inputStream().use { load(it) } }

val localReleaseBuild = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}["LOCAL_RELEASE_BUILD"]?.toString()?.toBooleanStrictOrNull() ?: false

android {
    namespace = "com.anopticlabs.gravel.router.email"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jan_leila.apps.email"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = routerVersion.getProperty("versionCode").toInt()
        versionName = routerVersion.getProperty("versionName")
    }

    if (routerKeystore != null && !localReleaseBuild) {
        signingConfigs {
            create("routers") {
                storeFile = file(routerKeystore)
                storePassword = System.getenv("ROUTER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ROUTER_KEY_ALIAS") ?: "gravel-routers"
                keyPassword = System.getenv("ROUTER_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (localReleaseBuild) {
                signingConfig = signingConfigs.getByName("debug")
            } else if (routerKeystore != null) {
                signingConfig = signingConfigs.getByName("routers")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":routers:common"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
