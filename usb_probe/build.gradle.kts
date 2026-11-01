plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "com.example.usbprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.usbprobe"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-cdc-probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }
}
