// ============================================================
// TTS-Server 移植模块 · lib-script（Rhino 运行时 + ttsrv 桥）
// 来源：jing332/tts-server-android @ compose（49b4a7c334）+ 移植适配
// 说明：
//  - Rhino 统一用主工程版本（org.mozilla:rhino:1.8.1）
//  - 原 com.drake.net 已替换为 okhttp（见 com.github.jing332.compat.net.HostHttp）
//  - 原 kotlin-logging 已替换为 com.github.jing332.compat.log.KLog
// ============================================================
plugins {
    alias(libs.plugins.android.library)
}

android {
    compileSdk = 37
    namespace = "com.github.jing332.script"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles += file("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}

dependencies {
    api(project(":modules:lib-common"))

    // Rhino：与主工程 modules/rhino 同一坐标（当前 1.8.1）
    api(libs.mozilla.rhino)

    // hutool 分体包（与主工程同坐标，避免 hutool-all 重复类）
    implementation("cn.hutool:hutool-core:5.8.22")
    implementation("cn.hutool:hutool-crypto:5.8.22")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation(libs.splitties.appctx)
    implementation("org.apache.commons:commons-text:1.15.0")
    // WebView 结果类型使用内置迷你实现（com.github.jing332.script.result），无第三方依赖
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
