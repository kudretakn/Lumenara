plugins { id("com.android.library") }
android {
    namespace = "dev.lumenara.vlc"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.videolan.android:libvlc-all:3.7.7")
    implementation(project(":core:data"))
    testImplementation("junit:junit:4.13.2")
}
