plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.manga.translator.translate"
    compileSdk = 34

    defaultConfig { minSdk = 29 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    implementation("com.google.mlkit:translate:17.0.3")
    // 网页语言识别（bundled，约 +900KB，本地推理）：2026-11-01「网页自动检测源语言」。
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("org.opencv:opencv:4.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

