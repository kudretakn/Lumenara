plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android {
    namespace = "dev.lumenara.design"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    testImplementation(libs.junit)
}
