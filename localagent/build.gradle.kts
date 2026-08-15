plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.voiceinteractionappsample.localagent"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":session"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
    // §2.4-1: library モジュールはローカル AAR を直接 implementation できない(AGP 制約)。
    // コンパイルのみここで参照し、ランタイム同梱は :app 側の implementation(files(...)) が担う。
    compileOnly(files("libs/sherpa-onnx-1.13.5.aar"))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
