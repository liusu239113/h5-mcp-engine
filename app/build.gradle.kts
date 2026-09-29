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
        minSdk = 26
        targetSdk = 34
        versionCode = 27
        versionName = "1.26"
        // GeckoView 只提供 arm64-v8a 变体：显式过滤 ABI，避免打出别的架构一进预览就崩
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(ks.getProperty("storeFile") ?: "keystore/hexora.jks")
            storePassword = ks.getProperty("storePassword") ?: "Hexora2026"
            keyAlias = ks.getProperty("keyAlias") ?: "hexora"
            keyPassword = ks.getProperty("keyPassword") ?: "Hexora2026"
            // 签名方案轮换（B4）：只签 v1/v2 的话，Android 11+ 会走「签名校验降级」路径，
            // 而且以后要换签名算法必须靠 v3 的 proof-of-rotation。v4 让增量安装更快。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
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
        // GeckoView 153 拉来的 androidx 新版是用 Kotlin 2.x 编译的（metadata > 2.0），
        // 本项目 Kotlin 1.9 编译器默认读不了；这些类只被 GeckoView 当普通字节码调用，
        // 不涉及任何 Kotlin 2.x 语言特性，跳过 metadata 校验是安全的。
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    // 游戏包（zip/wasm/data）不压缩，运行时可直接随机读
    androidResources {
        noCompress += listOf("zip", "wasm", "data", "json")
    }
    // 关键：把 jniLibs 按传统方式解压到 nativeLibraryDir。
    // 我们靠自己带的 musl 加载器 exec node，而安卓只允许从 lib 目录执行，
    // 所以 libmuslrt.so 必须真实落地（新版 AGP 默认不再解压）。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libmuslrt.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")
    // Maker 预览通道：GeckoView（Firefox 内核）。
    // 系统 WebView 没有站点隔离（Fission）→ crossOriginIsolated=false → SharedArrayBuffer 不可用
    // → UrhoX 的 WASM 多线程引擎起不来（白屏）；GeckoView 默认开 Fission，原生支持。
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:153.0.20260715202819")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // C3 内嵌 JS 引擎：Rhino（纯 JVM 实现，免 native so / 免子进程、无 ABI 问题，也不受
    // SELinux「禁 execve 私有目录」限制）。给插件体系(D1) 与工作流脚本节点提供受限执行环境。
    implementation("org.mozilla:rhino:1.7.15")
    // D2 web-chat / a2a-server：内嵌轻量 HTTP 服务（NanoHTTPD，纯 Java，无 native）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

// ---------------------------------------------------------------------------
// GeckoView 153 的传递依赖压制
//   androidx.core:*   声明 minCompileSdk>=36，本项目 compileSdk=34 → 压到 1.13.1
//   androidx.media3:* 声明 minCompileSdk>=36 → 压到 1.3.1（最后一个 minCompileSdk=34 的版本）
//   kotlin-stdlib 被拉到 2.x（metadata 2.x），Kotlin 1.9 编译器读不了 → 压到 1.9.24
// GeckoView 只用到这些库的基础 API，降版本不影响运行。
// ---------------------------------------------------------------------------
configurations.configureEach {
    resolutionStrategy {
        force("androidx.core:core:1.13.1")
        force("androidx.core:core-ktx:1.13.1")
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.24")
        force("androidx.media3:media3-common:1.3.1")
        force("androidx.media3:media3-datasource:1.3.1")
        force("androidx.media3:media3-decoder:1.3.1")
        force("androidx.media3:media3-exoplayer:1.3.1")
        force("androidx.media3:media3-exoplayer-hls:1.3.1")
        force("androidx.media3:media3-container:1.3.1")
        force("androidx.media3:media3-extractor:1.3.1")
        force("androidx.media3:media3-database:1.3.1")
    }
}
