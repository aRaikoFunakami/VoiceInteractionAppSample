plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.voiceinteractionappsample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.voiceinteractionappsample"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // third_party/libwebrtc: AAOS Emulator (x86_64) + arm64-v8a devices only.
        // 32bit not included until a real requirement shows up.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":via"))
    implementation(project(":diagnostics"))
    implementation(project(":realtime")) // ServerSettingsActivity (issue #43)
    implementation(project(":localagent")) // モデル配置チェック (issue #48)
    // sherpa-onnx AAR のランタイム同梱。:localagent は AGP 制約(AAR-in-library 禁止)により
    // compileOnly のため、実行時クラスはここで供給する (docs/local-voice-agent-dev-plan.md §2.4-1)
    implementation(files("../localagent/libs/sherpa-onnx-1.13.5.aar"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.litertlm.android) // SpikeToolCallTest (issue #50)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}