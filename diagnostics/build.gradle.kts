plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.voiceinteractionappsample.diagnostics"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // diagnostics reads state from every other module (18-21節: WebRTC stats, audio
    // device info, AEC capability, session state, tool state)
    implementation(project(":realtime"))
    implementation(project(":audio"))
    implementation(project(":tools"))
    implementation(project(":session"))
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
