// Gravel module: what every first-party router app shares (routers/README.md): the router API's
// names, the receiver that takes a delivery and acknowledges it, the outbox the delivery waits in
// until it is sent, the worker that sends it, the HTTP and secret helpers, and the settings
// screen, and the list of routers an app shows Gravel beside its declared one. A plain Android
// library: the apps are Android-only and tiny.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.anopticlabs.gravel.router.common"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.work)
    api(libs.androidx.activity.compose)
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.serialization)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
