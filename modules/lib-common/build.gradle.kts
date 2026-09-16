// ============================================================
// TTS-Server 移植模块 · lib-common（子集）
// 来源：jing332/tts-server-android @ compose（49b4a7c334）+ 移植适配
// 说明：坐标如需统一到主工程版本目录，可直接替换为 libs.* 引用
// ============================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize) // LogEntry 使用 @Parcelize
}

android {
    compileSdk = 37
    namespace = "com.github.jing332.common"

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
    // androidx.annotation：本模块与下游模块（lib-script/lib-tts）编译期都需要（@Keep/@IntDef/@StringRes）
    api("androidx.annotation:annotation:1.10.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.fragment:fragment:1.9.0") // ToastUtils 的 Fragment 扩展函数
    // hutool 分体包（与主工程 hutool-crypto 同坐标，避免 hutool-all 重复类）
    implementation("cn.hutool:hutool-crypto:5.8.22") // MD5（传递依赖 hutool-core）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
}
