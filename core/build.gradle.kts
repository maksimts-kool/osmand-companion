plugins {
    id("com.android.library")
}

android {
    namespace = "dev.maksim.companion.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // OsmAnd AIDL API (parcelables + IOsmAndAidlInterface). Same artifact the official osmand-api-demo uses.
    api("net.osmand:android-aidl-lib:master-snapshot@aar")

    api("androidx.appcompat:appcompat:1.7.1")
    api("com.google.android.material:material:1.14.0")
}
