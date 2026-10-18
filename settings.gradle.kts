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
    }
}
rootProject.name = "ozhungu"

// Consume the zo-kotlin SDK from source when checked out next to this repo
// (../zo-kotlin); otherwise fall back to the published Maven artifact.
val zoKotlinDir = file("../zo-kotlin")
if (zoKotlinDir.resolve("settings.gradle.kts").exists()) {
    includeBuild(zoKotlinDir)
} else {
    logger.lifecycle("zo-kotlin source build not found; using Maven artifact dev.zocomputer:ask")
}
include(":app")
