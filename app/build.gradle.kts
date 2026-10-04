import java.util.Properties

plugins {
    id("com.android.application")
}

// The version lives in gradle.properties (appVersion=major.minor.patch). The release workflow overrides it
// with the tag: v2.1.0 builds with -PappVersion=2.1.0, a beta's v2.1.0-beta.1 with -PappVersion=2.1.0-beta.1.
// versionCode follows from it, so each release installs over the previous one, and a beta sits between the release
// before it and its own: 2.1.0-beta.1 → 2010001, 2.1.0-beta.2 → 2010002, 2.1.0 → 2010099. (Up to 2.2.4 it was
// 20204, without the last two digits; every code since is bigger.)
val appVersion = providers.gradleProperty("appVersion").get()
val appVersionCode = Regex("""(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?""").matchEntire(appVersion)
    ?.groupValues?.drop(1)?.map { it.toIntOrNull() }
    ?.takeIf { parts -> parts.take(3).all { it != null && it in 0..99 } && parts[3].let { it == null || it in 1..98 } }
    ?.let { parts -> (parts[0]!! * 10000 + parts[1]!! * 100 + parts[2]!!) * 100 + (parts[3] ?: 99) }
    ?: error("appVersion must be major.minor.patch (each part 0–99), or that with -beta.N (1–98), got \"$appVersion\"")

// The release key: keystore.properties locally (not in git, see README), environment variables on CI.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)
val releaseStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")

// Sentry's DSN, for crash reports and usage stats (README): local.properties locally, an environment variable on CI.
// A build without it has no analytics, and doesn't ask the user about it.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun analyticsKey(key: String, env: String): String = localProperties.getProperty(key) ?: System.getenv(env).orEmpty()

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
        buildConfigField("String", "SENTRY_DSN", "\"${analyticsKey("sentryDsn", "SENTRY_DSN")}\"")
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

    // Lists the translations (values-ru, …) so Android 13+ offers them under Settings → Apps → Language.
    // res/resources.properties says which language the untranslated values/ strings are in.
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":feature:timetable"))
    implementation(project(":feature:planner"))
    // The daily update check.
    implementation("androidx.work:work-runtime:2.12.0")
    // The update popup's animation; the same version as the timetable feature's.
    implementation("com.airbnb.android:lottie:6.7.1")

    testImplementation("junit:junit:4.13.2")
}
