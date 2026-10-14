plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.manga.translator.app"
    compileSdk = 34

defaultConfig {
        applicationId = "com.manga.translator.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-capture"))
    implementation(project(":core-translate"))
    implementation(project(":core-overlay"))
    implementation(project(":core-store"))

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

testImplementation("junit:junit:4.13.2")
    // BrowserScanScript 用 org.json 拼注入配置；Android 的 org.json 在 JVM 单测里是
    // 桩实现（方法体抛异常），必须显式加一份真实现才能测 JSON 内容。
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    // MlKitConfidenceProbeTest 需要直接调 ML Kit 识别器；core-translate 用的是
    // implementation 依赖，编译期不会传递给 app 的测试源集，只能在这里再声明一次。
    androidTestImplementation("com.google.mlkit:text-recognition:16.0.1")
    androidTestImplementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    androidTestImplementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    androidTestImplementation("com.google.mlkit:text-recognition-korean:16.0.0")
    androidTestImplementation("com.google.android.gms:play-services-tasks:18.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


