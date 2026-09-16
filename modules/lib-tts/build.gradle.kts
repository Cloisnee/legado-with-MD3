// ============================================================
// TTS-Server 移植模块 · lib-tts（插件引擎 + 运行时宿主）
// 来源：jing332/tts-server-android @ compose（49b4a7c334）+ 移植适配
// 说明：
//  - 已去除 compose / lib-server（ktor / netty）/ room DAO
//  - com.drake.net 已替换为 okhttp（HostHttp）
//  - 数据实体保留 Room 注解但仅作编译（compileOnly），实际存储走宿主侧适配（阶段2）
// ============================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)      // 实体 @Parcelize
    alias(libs.plugins.kotlin.serialization)  // 实体 @Serializable
}

android {
    compileSdk = 37
    namespace = "com.github.jing332.tts"

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
    api(project(":modules:lib-script"))

    // hutool 分体包：AbstractCachedManager 使用 TimedCache（避免 hutool-all 重复类）
    implementation("cn.hutool:hutool-cache:5.8.22")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // WebSocket 与网络桥（TtsPluginEngineV2/JWebSocket/WebSocketClient 直接使用；与主工程同坐标）
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    // 实体上的 @Entity/@ColumnInfo 仅编译期需要（本模块不含 DAO/数据库）
    compileOnly("androidx.room:room-runtime:2.8.4")
}
