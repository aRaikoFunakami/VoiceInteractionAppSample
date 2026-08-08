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

dependencies {
    // Pinned prebuilt Google WebRTC AAR — see third_party/libwebrtc/README.md for why
    // this isn't built from source, and third_party/libwebrtc/VERSION for the pin.
    api(libs.stream.webrtc.android)
}
