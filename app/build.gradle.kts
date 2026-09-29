import java.util.Properties

plugins {
    id("com.android.application")
}

// The version lives in gradle.properties (appVersion=major.minor.patch). The release workflow overrides it
// with the tag: v2.1.0 builds with -PappVersion=2.1.0. versionCode follows from it, so each release installs
// over the previous one: 2.1.0 → 20100.
val appVersion = providers.gradleProperty("appVersion").get()
val appVersionCode = appVersion.split('.').map { it.toIntOrNull() }.let { parts ->
    require(parts.size == 3 && parts.all { it != null && it in 0..99 }) {
        "appVersion must be major.minor.patch with each part 0–99, got \"$appVersion\""
    }
    parts[0]!! * 10000 + parts[1]!! * 100 + parts[2]!!
}

// The release key: keystore.properties locally (not in git, see README), environment variables on CI.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)
val releaseStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")

android {
    namespace = "dev.maksim.companion"
    compileSdk = 36

    defaultConfig {
        // Kept from when the app was only Route Logger, so an update keeps its settings
        // and the permission granted in OsmAnd → Plugins.
        applicationId = "dev.maksim.routelogger"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val release = signingConfigs.findByName("release")
        release {
            isMinifyEnabled = false
            signingConfig = release
        }
        debug {
            // Same key as the releases when it's available, so a debug build and a GitHub update can replace
            // each other without uninstalling (which would lose the settings).
            if (release != null) signingConfig = release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":feature:routelogger"))
    implementation(project(":feature:timetable"))
    // The daily update check.
    implementation("androidx.work:work-runtime:2.12.0")
}
