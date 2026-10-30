plugins {
    id("com.android.application")
}

android {
    namespace = "dev.transparentbridge.usbprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.transparentbridge.usbprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
