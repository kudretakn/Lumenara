plugins { id("com.android.library") }
android {
    namespace = "dev.lumenara.player"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    api("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation(project(":core:data"))
    testImplementation("junit:junit:4.13.2")
}
