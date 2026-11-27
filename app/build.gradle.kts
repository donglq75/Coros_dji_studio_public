import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.corosdjistudio"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.corosdjistudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 90
        versionName = "1.0.85-orientation-result-display-fix"
        // Final-pass encoding bakes orientation and clears inherited MP4 rotation metadata.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        create("vgTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".vgtest"
            versionNameSuffix = "-vgtest"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // VoiceDetector uses the ORT Java API; keep the app runtime aligned with
        // the 1.20.0 native runtime bundled by VietASR 0.1.0.
        jniLibs.pickFirsts += setOf(
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/armeabi-v7a/libonnxruntime.so",
            "lib/x86/libonnxruntime.so",
            "lib/x86_64/libonnxruntime.so"
        )
    }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("io.github.dangvansam:viet-asr-android:0.1.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}


// VietASR 0.1.0's Java binding loads libvietasr_jni.so, while its Android
// CMake target currently packages the JNI entry points inside libvietasr.so.
// Add the expected soname alias to the APK so Pipeline's static loader can
// complete. The JNI symbols are already exported by libvietasr.so.
val vietAsrJniAliasDir = layout.buildDirectory.dir("generated/vietasr-jni-alias")
android.sourceSets.getByName("main").jniLibs.srcDir(vietAsrJniAliasDir)

val generateVietAsrJniAlias = tasks.register("generateVietAsrJniAlias") {
    val outputDir = vietAsrJniAliasDir
    outputs.dir(outputDir)
    doLast {
        val aar = configurations.getByName("vgTestRuntimeClasspath").files
            .firstOrNull { it.name.startsWith("viet-asr-android-") && it.extension == "aar" }
            ?: error("Không tìm thấy AAR viet-asr-android trong vgTestRuntimeClasspath")
        val targetDir = outputDir.get().dir("arm64-v8a").asFile
        targetDir.mkdirs()
        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("jni/arm64-v8a/libvietasr.so")
                ?: error("AAR VietASR thiếu jni/arm64-v8a/libvietasr.so")
            zip.getInputStream(entry).use { input ->
                targetDir.resolve("libvietasr_jni.so").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        check(targetDir.resolve("libvietasr_jni.so").length() > 0L) {
            "Không tạo được libvietasr_jni.so alias"
        }
    }
}

tasks.configureEach {
    // Both Studio's debug APK and the standalone VG Test APK load the same
    // VietASR Java binding. Ensure the JNI soname alias is packaged in both.
    if (name == "mergeDebugJniLibFolders" ||
        name == "mergeDebugNativeLibs" ||
        name == "mergeVgTestJniLibFolders" ||
        name == "mergeVgTestNativeLibs") {
        dependsOn(generateVietAsrJniAlias)
    }
}
