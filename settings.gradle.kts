pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // OsmAnd publishes its AIDL client library (net.osmand:android-aidl-lib) here.
        ivy {
            name = "OsmAndBinariesIvy"
            url = uri("https://builder.osmand.net")
            patternLayout {
                artifact("ivy/[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]")
            }
            metadataSources { artifact() }
        }
    }
}

rootProject.name = "osmand-companion"

// :app is the shell (home screen, OsmAnd status, log); each feature is its own module on top of :core.
include(":app")
include(":core")
include(":feature:timetable")
include(":feature:planner")
