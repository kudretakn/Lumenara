pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
// WP20 comparisons use a fresh local task cache without erasing shared dependency caches.
providers.gradleProperty("wp20.buildCacheDir").orNull?.let { cacheDirectory ->
    buildCache { local { directory = file(cacheDirectory) } }
}
rootProject.name = "Lumenara"
include(":app", ":core:catalog", ":core:designsystem")
include(":core:data", ":core:player")
include(":core:player-api", ":core:player-vlc")
// The producer is tooling, never part of an ordinary build's device task graph.
val wp20GenerateProfiles = providers.gradleProperty("wp20.generateProfiles").getOrElse("false").toBooleanStrict()
val wp20BootstrapDependencies = providers.gradleProperty("wp20.bootstrapDependencies").getOrElse("false").toBooleanStrict()
if (wp20BootstrapDependencies && !wp20GenerateProfiles) {
    // Bootstrap must not become a way to expose ungated connected/installation tasks.
    val wp20BootstrapTasks = setOf("wp20ResolveAndLockAll", ":baselineprofile:assembleNonMinifiedRelease", ":baselineprofile:assembleBenchmarkRelease")
    require(gradle.startParameter.taskNames.isNotEmpty() &&
        gradle.startParameter.taskNames.all { it in wp20BootstrapTasks }) {
        "WP20 dependency bootstrap permits only wp20ResolveAndLockAll or :baselineprofile:assemble{NonMinified,Benchmark}Release; profile/device tasks require the emulator gate"
    }
}
if (wp20GenerateProfiles) {
    val serial = providers.environmentVariable("ANDROID_SERIAL").orNull
        ?: error("WP20 profile generation requires an explicit ANDROID_SERIAL")
    // A Provider-backed process is rechecked even when configuration cache is reused.
    // This runs before any project can install or instrument an APK.
    val gate = providers.exec {
        commandLine("python3", file("baselineprofile/Wp20ProfileGate.py").absolutePath,
            "--serial", serial, "--allowlist", file("baselineprofile/Wp20EmulatorSerials.txt").absolutePath)
    }
    check(gate.standardOutput.asText.get().trim() == "WP20_API36_EMULATOR_ALLOWED")
}
if (wp20GenerateProfiles || wp20BootstrapDependencies) include(":baselineprofile")
