plugins {
    id("com.android.application")
}

android {
    namespace = "dev.maksim.companion"
    compileSdk = 36

    defaultConfig {
        // Kept from when the app was only Route Logger, so an update keeps its settings
        // and the permission granted in OsmAnd → Plugins.
        applicationId = "dev.maksim.routelogger"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
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
    implementation(project(":core"))
    implementation(project(":feature:routelogger"))
    implementation(project(":feature:timetable"))
}
