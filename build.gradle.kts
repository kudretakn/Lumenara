import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

plugins {
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// WP20: locks cover every resolvable project configuration, including test/tooling
// dependencies. Plugin versions come from the catalog; verification also covers plugins.
allprojects {
    val wp20Module = if (path == ":") "root" else path.removePrefix(":").replace(':', '-')
    if (providers.gradleProperty("wp20.generateProfiles").getOrElse("false").toBooleanStrict()) {
        // Isolate collection intermediates; saveInSrc still writes app/src/main profiles.
        layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/wp20-profile-candidates/$wp20Module"))
    }
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
        lockFile.set(rootProject.layout.projectDirectory.file("gradle/wp20-locks/$wp20Module.lockfile"))
    }
    tasks.register("wp20ResolveAndLockAll") {
        group = "build setup"
        description = "Resolve all WP20 lock inputs; only the lead runs this with --write-locks."
        notCompatibleWithConfigurationCache("Dependency bootstrap inspects configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "WP20 bootstrap requires --write-locks" }
        }
        doLast {
            // Lock the graph without demanding unbuilt project AAR/JAR artifacts.
            // The normal debug/QA/release/tool assemblies then verify actual artifact bytes.
            configurations.filter { it.isCanBeResolved }.forEach { configuration ->
                configuration.incoming.resolutionResult.allDependencies.forEach { dependency ->
                    if (dependency is UnresolvedDependencyResult) {
                        throw GradleException("WP20 cannot lock ${configuration.name}", dependency.failure)
                    }
                }
            }
        }
    }
}
