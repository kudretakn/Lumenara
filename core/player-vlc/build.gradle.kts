plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.lumenara.vlc"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(libs.coroutines.core)
    api(libs.vlc)
    api(project(":core:player-api"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
