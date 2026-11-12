plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.omarchy.gliff"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.omarchy.gliff"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.0-preview.4"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.window:window-java:1.5.1")
    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
