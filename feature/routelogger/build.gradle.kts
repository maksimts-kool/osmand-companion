plugins {
    id("com.android.library")
}

android {
    namespace = "dev.maksim.companion.routelogger"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core"))
    // Retries Telegram delivery until the network is back.
    implementation("androidx.work:work-runtime:2.12.0")
}
