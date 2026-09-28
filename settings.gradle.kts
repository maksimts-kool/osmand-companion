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

rootProject.name = "osmand-route-logger"
include(":app")
