plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.voiceinteractionappsample.audio"
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

dependencies {
    // org.webrtc.audio.JavaAudioDeviceModule lives in the same pinned AAR as :realtime.
    // See third_party/libwebrtc/README.md.
    api(libs.stream.webrtc.android)
}
