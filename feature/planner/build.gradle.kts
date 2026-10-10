plugins {
    id("com.android.library")
}

android {
    namespace = "dev.maksim.companion.planner"
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
    // peatus.ee, the live feeds, and the timetable screens' look, which the planner shares.
    implementation(project(":feature:timetable"))
    implementation("com.airbnb.android:lottie:6.7.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in unit tests.
    testImplementation("org.json:json:20250517")
}
