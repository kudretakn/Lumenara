plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room") version "2.8.4"
}
android {
    namespace = "dev.streamora.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
room { schemaDirectory("$projectDir/schemas") }
dependencies {
    api(project(":core:catalog"))
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
}
