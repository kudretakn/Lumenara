pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Lumenara"
include(":app", ":core:catalog", ":core:designsystem")
include(":core:data", ":core:player")
include(":core:player-vlc")
