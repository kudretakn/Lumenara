plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}
android {
    namespace = "dev.lumenara.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // WP10: AGP's native strip task and CI verification use this exact NDK.
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        applicationId = "dev.lumenara.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 21
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // WP15: release publication opts in; ordinary debug/QA and WP10's universal
    // build evidence keep their existing APK paths. These are standalone APKs,
    // all with the unchanged defaultConfig versionCode (no per-ABI offsets).
    // Lead: -Pwp15.abiSplits=true :app:assembleRelease
    splits {
        abi {
            isEnable = providers.gradleProperty("wp15.abiSplits").getOrElse("false").toBooleanStrict()
            reset()
            include("armeabi-v7a", "arm64-v8a")
            // Exactly one ARM-universal fallback lets installed pre-WP15 updaters upgrade.
            isUniversalApk = true
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug" }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // AGP retains mapping.txt/configuration.txt under build/outputs/mapping/release.
        }
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
            proguardFile("proguard-qa-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            // Same optimizer/rules as release; mapping is under build/outputs/mapping/qa.
        }
    }
    // AGP instruments one build type per invocation. Ordinary debug checks stay unchanged.
    // Lead: -Pwp02.testBuildType=qa :app:assembleQa :app:assembleQaAndroidTest
    testBuildType = providers.gradleProperty("wp02.testBuildType").getOrElse("debug").also {
        require(it == "debug" || it == "qa") { "wp02.testBuildType must be debug or qa" }
    }
    if (testBuildType == "qa") {
        // Black-box QA tests do not keep app implementation APIs alive just for instrumentation.
        sourceSets.getByName("androidTest") {
            java.setSrcDirs(listOf("src/androidTestQa/java"))
            kotlin.setSrcDirs(listOf("src/androidTestQa/java"))
        }
    }
    // Android TV boxes are ARM; x86 libraries serve debug/profile collection and optimized QA.
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(listOf("lib/x86/**", "lib/x86_64/**"))
    }
    androidComponents.onVariants(androidComponents.selector().withBuildType("qa")) { variant ->
        // API28 Android TV is x86-only; API36 uses x86_64. Keep both for QA.
        variant.packaging.jniLibs.excludes.addAll(listOf("lib/armeabi-v7a/**", "lib/arm64-v8a/**"))
    }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("core/data/schemas"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
// Opt in for WP17's stability review; no stability allowlist or compiler behavior changes.
// Lead: -Pwp02.composeReports=true :app:assembleRelease
composeCompiler {
    if (providers.gradleProperty("wp02.composeReports").getOrElse("false").toBooleanStrict()) {
        reportsDestination = layout.buildDirectory.dir("reports/compose/reports")
        metricsDestination = layout.buildDirectory.dir("reports/compose/metrics")
    }
}
baselineProfile {
    automaticGenerationDuringBuild = false
    // Plugin 1.5.0 requires source saving when automatic generation is off.
    saveInSrc = true
    mergeIntoMain = true
    // Generated profiles go to the plugin's default src/<variant>/generated/baselineProfiles;
    // the hand-kept src/main/baseline-prof.txt is read by AGP directly.
    dexLayoutOptimization = false
}
val wp20GenerateProfiles = providers.gradleProperty("wp20.generateProfiles").getOrElse("false").toBooleanStrict()
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("BaselineProfile") }.configureEach {
    doFirst {
        check(wp20GenerateProfiles) { "WP20 profile generation requires -Pwp20.generateProfiles=true and the emulator gate" }
    }
}
dependencies {
    implementation(libs.profileinstaller)
    if (findProject(":baselineprofile") != null) baselineProfile(project(":baselineprofile"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(project(":core:designsystem"))
    implementation(project(":core:catalog"))
    implementation(project(":core:data"))
    implementation(project(":core:player-api"))
    implementation(project(":core:player"))
    implementation(project(":core:player-vlc"))
    implementation(libs.work.runtime)
    implementation(libs.coil.svg)
    implementation(libs.coil.compose)
    implementation(libs.zxing)
    implementation(libs.okhttp.tls)
    androidTestImplementation(libs.okhttp.mockwebserver)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.tv.material)
    implementation(libs.tvprovider)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test.junit4)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.test.manifest)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.uiautomator)
}
