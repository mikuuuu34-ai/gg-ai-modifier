plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.yl.aigg.ai_gg666"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.yl.aigg.ai_gg666"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // MCP 服务：轻量 HTTP 服务端（Streamable HTTP 传输）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // MemoryEngine / RootScanner 用到协程；原先靠传递依赖引入，显式声明避免版本漂移
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

flutter {
    source = "../.."
}

// scanner_root 走 assets 分发（放 jniLibs 会被重命名成 lib*.so，丢掉可执行属性），
// 所以必须在「资源合并」之前把 CMake 刚编出来的产物刷进 src/main/assets。
tasks.register("copyScannerRoot") {
    // 输入是 CMake 产物目录，输出是 assets，声明出来让 Gradle 正确判断是否需要重跑
    outputs.upToDateWhen { false }

    doLast {
        val buildDirFile = layout.buildDirectory.get().asFile
        val abiList = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        var copied = 0
        abiList.forEach { abi ->
            var srcFile: File? = null

            // 路径形如 intermediates/cxx/{Debug,Release}/$hash/obj/$abi/scanner_root
            // 注意 AGP 8 用的是首字母大写的 Debug/Release
            for (variant in listOf("Release", "Debug", "release", "debug")) {
                for (prefix in listOf("cxx", "cmake")) {
                    val baseDir = File("$buildDirFile/intermediates/$prefix/$variant")
                    if (!baseDir.exists()) continue
                    baseDir.listFiles()?.forEach { hashDir ->
                        if (!hashDir.isDirectory) return@forEach
                        val candidate = File(hashDir, "obj/$abi/scanner_root")
                        if (candidate.exists() && (srcFile == null ||
                                    candidate.lastModified() > srcFile!!.lastModified())) {
                            srcFile = candidate
                        }
                    }
                }
            }

            val found = srcFile
            if (found != null) {
                val destDir = file("src/main/assets/native/$abi")
                val destFile = file("$destDir/scanner_root")
                destDir.mkdirs()
                found.copyTo(destFile, overwrite = true)
                println("✅ copyScannerRoot: $abi ← ${found.absolutePath} (${found.length()} bytes)")
                copied++
            } else {
                println("⚠️ copyScannerRoot: 未找到 $abi 的 scanner_root")
            }
        }

        if (copied == 0) {
            throw GradleException(
                "copyScannerRoot 没有找到任何 ABI 的 scanner_root 产物。" +
                "若继续打包，APK 里会是仓库中那份陈旧的预编译二进制，" +
                "对 C++ 的所有修改都不会生效。"
            )
        }
    }
}

// 把 copyScannerRoot 正确嵌进任务图。
// 原实现只挂在 externalNativeBuildDebug / mergeDebugNativeLibs / buildCMakeDebug 上，
// release 构建根本不会触发，导致 `flutter build apk --release` 打包的是仓库里
// 提交的旧二进制 —— C++ 改了也白改。
afterEvaluate {
    val nativeTaskNames = tasks.names.filter { it.startsWith("externalNativeBuild") }

    // 复制动作必须排在所有 CMake 产物生成之后
    tasks.named("copyScannerRoot").configure {
        mustRunAfter(*nativeTaskNames.toTypedArray())
    }

    // 每个变体的资源合并都要先等对应的 CMake 构建 + 复制完成
    listOf("Debug", "Release", "Profile").forEach { variant ->
        val mergeTask = tasks.findByName("merge${variant}Assets") ?: return@forEach
        val nativeTask = tasks.findByName("externalNativeBuild$variant")
        if (nativeTask != null) {
            mergeTask.dependsOn(nativeTask)
        }
        mergeTask.dependsOn("copyScannerRoot")
        println("✅ merge${variant}Assets 已串上 copyScannerRoot")
    }
}
