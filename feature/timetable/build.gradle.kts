plugins {
    id("com.android.library")
}

android {
    namespace = "dev.maksim.companion.timetable"
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
    // Loading, empty and offline animations (from LottieFiles, see README).
    implementation("com.airbnb.android:lottie:6.7.1")
}
