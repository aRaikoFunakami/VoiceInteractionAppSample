plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.voiceinteractionappsample.realtime"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
