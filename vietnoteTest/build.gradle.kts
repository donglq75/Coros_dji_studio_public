plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.corosdjistudio.vietnotetest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.corosdjistudio.vietnotetest"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-vietnote-test"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.3-static-link-onnxruntime.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
