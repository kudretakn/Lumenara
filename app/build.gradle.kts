plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}
android {
    namespace = "dev.lumenara.tv"
    compileSdk = 36
    // WP10: AGP's native strip task and CI verification use this exact NDK.
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.lumenara.tv"
        minSdk = 26
        targetSdk = 36
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
    saveInSrc = true
    mergeIntoMain = true
    dexLayoutOptimization = true
}
dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")
    implementation(project(":core:designsystem"))
    implementation(project(":core:catalog"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(project(":core:player-vlc"))
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(platform("androidx.compose:compose-bom:2026.05.00"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.room:room-runtime:2.8.4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
