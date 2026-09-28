plugins {
    id("com.android.application")
}

android {
    namespace = "dev.maksim.routelogger"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.maksim.routelogger"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    // OsmAnd AIDL API (parcelables + IOsmAndAidlInterface). Same artifact the official osmand-api-demo uses.
    implementation("net.osmand:android-aidl-lib:master-snapshot@aar")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    // Retries Telegram delivery until the network is back.
    implementation("androidx.work:work-runtime:2.12.0")
}
