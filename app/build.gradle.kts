plugins {
    id("com.android.application")
}

android {
    namespace = "dev.maksim.osmandsample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.maksim.osmandsample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
}
