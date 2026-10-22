plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.icegps.voiceime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.icegps.voiceime"
        minSdk = 26
        targetSdk = 35
        versionCode = 90
        // V2：端侧转写（MNN Qwen3-ASR-0.6B INT8）+ 局域网备用
        versionName = "0.90.0-v90"

        // MNN native bridge：只在有 NDK 时编译（arm64-v8a）。
        // 无 NDK 的构建（如 Linux CI）跳过 native，APK 仍可编译安装，
        // MnnAsrBackend 在运行时检测到缺库会自动降级到局域网。
        if (hasNdk()) {
            externalNativeBuild {
                cmake {
                    abiFilters += "arm64-v8a"
                    arguments += "-DANDROID_STL=c++_shared"
                    val mnnRoot = project.findProperty("mnnRoot")?.toString()
                        ?: System.getenv("MNN_ROOT")
                        ?: ""
                    arguments += "-DMNN_ROOT=$mnnRoot"
                }
            }
        }
    }

    // 同上：无 NDK 时不配置 externalNativeBuild，保证纯 Kotlin 也能编过。
    if (hasNdk()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // 调试版保留完整符号，方便排错
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // jniLibs 下的预编译 libMNN.so 会自动打进 APK（.gitignore 排除版本控制）
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

/**
 * NDK 是否可用：ANDROID_NDK_HOME / ANDROID_HOME/ndk/* / local.properties 的 ndk.dir。
 * 纯 Kotlin 构建不需要 NDK（MNN bridge 跳过，运行时降级）。
 */
fun hasNdk(): Boolean {
    val envNdk = System.getenv("ANDROID_NDK_HOME")?.let(::File)?.takeIf { it.isDirectory }
    if (envNdk != null) return true
    val sdkNdk = System.getenv("ANDROID_HOME")?.let { File(it, "ndk") }
        ?.listFiles()?.any { it.isDirectory } ?: false
    if (sdkNdk) return true
    val propNdk = project.findProperty("ndk.dir")?.toString()?.let(::File)?.takeIf { it.isDirectory }
    return propNdk != null
}

dependencies {
    // WebSocket 客户端（复刻 Windows 端 websockets 库的行为，局域网转写用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // sherpa-onnx 端侧语音识别（预编译 AAR，app/libs/sherpa-onnx-*.aar）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
