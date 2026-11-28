plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.lumenara.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.profileMinSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Rechecked inside instrumentation before BaselineProfileRule.collect touches app data.
        testInstrumentationRunnerArguments["wp20.profileSerial"] =
            providers.environmentVariable("ANDROID_SERIAL").getOrElse("")
    }
    targetProjectPath = ":app"
    buildTypes {
        create("release") { isDebuggable = true }
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Generation is explicit; ordinary test/lintDebug/debug APK tasks need no device.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

// Dependency bootstrap can resolve/assemble this tool without connecting to a device.
// Only the settings-level host gate enables connected-device task creation.
baselineProfile {
    useConnectedDevices = providers.gradleProperty("wp20.generateProfiles").getOrElse("false").toBooleanStrict()
}

dependencies {
    implementation(libs.benchmark.macro)
    implementation(libs.androidx.test.junit)
    implementation(libs.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.okhttp.mockwebserver)
}
