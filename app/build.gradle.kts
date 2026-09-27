import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名：优先读仓库根的 keystore.properties；私钥在 app/keystore/hexora.jks（已随仓库提交）
val ks = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mcp.h5engine"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mcp.h5engine"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.9"
    }

    signingConfigs {
        create("release") {
            storeFile = file(ks.getProperty("storeFile") ?: "keystore/hexora.jks")
            storePassword = ks.getProperty("storePassword") ?: "Hexora2026"
            keyAlias = ks.getProperty("keyAlias") ?: "hexora"
            keyPassword = ks.getProperty("keyPassword") ?: "Hexora2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 调试包也用正式签名，免得装来装去签名冲突
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // 游戏包（zip/wasm/data）不压缩，运行时可直接随机读
    androidResources {
        noCompress += listOf("zip", "wasm", "data", "json")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}