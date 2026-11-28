plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}
android {
    namespace = "dev.lumenara.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${System.getenv("OPENSUBTITLES_API_KEY").orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "TMDB_READ_TOKEN", "\"${System.getenv("TMDB_READ_TOKEN").orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
room { schemaDirectory("$projectDir/schemas") }
dependencies {
    api(project(":core:catalog"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    api(libs.okhttp.core)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}
