plugins { id("com.android.library") }
android {
    namespace = "dev.lumenara.vlc"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api("org.videolan.android:libvlc-all:3.7.7")
    implementation(project(":core:data"))
}
