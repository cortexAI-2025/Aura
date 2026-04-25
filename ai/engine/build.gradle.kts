plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aura.ai.engine"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        // Keep TFLite model files from being compressed — required for mmap
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    aaptOptions { noCompress += listOf("tflite") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))

    // MediaPipe on-device LLM inference (Gemma 2B/7B)
    implementation(libs.mediapipe.llm)

    // TFLite for MiniLM-L6-v2 INT8 embedding model
    implementation(libs.tflite.core)
    implementation(libs.tflite.gpu)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
