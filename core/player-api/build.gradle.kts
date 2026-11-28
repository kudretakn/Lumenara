plugins { alias(libs.plugins.kotlin.jvm); id("java-library") }
kotlin { jvmToolchain(17) }
dependencies {
    api(libs.coroutines.core)
    api(libs.okhttp.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
