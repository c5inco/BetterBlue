pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "BetterBlueAndroid"

// :betterbluekit is a pure-JVM Kotlin module and builds anywhere.
include(":betterbluekit")

// :app needs the Android SDK (and Google's Maven repository, which some sandboxed
// CI environments cannot reach). Gate it on SDK availability so `./gradlew build`
// stays useful for the kit in environments without an Android toolchain.
val localProperties = File(rootDir, "local.properties")
val sdkDirConfigured = localProperties.exists() &&
    localProperties.readLines().any { it.trim().startsWith("sdk.dir=") }
val androidSdkAvailable = sdkDirConfigured ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.warn(
        "Android SDK not found (no ANDROID_HOME and no sdk.dir in local.properties): " +
            "skipping the :app module. Only :betterbluekit will be built."
    )
}
