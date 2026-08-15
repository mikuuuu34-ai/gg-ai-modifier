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
    outputs.upToDateWhen { false }

    doLast {
        val buildDirFile = layout.buildDirectory.get().asFile
        val abiList = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        // AGP 8 的 release 变体，CMake 构建类型目录叫 RelWithDebInfo 而不是 Release，
        // 目录层级也随版本变动。与其枚举名字，不如把整棵中间产物树扫一遍取最新的那份。
        val searchRoots = listOf(
            File(buildDirFile, "intermediates/cxx"),
            File(buildDirFile, "intermediates/cmake")
        ).filter { it.exists() }

        val newest = HashMap<String, File>()
        searchRoots.forEach { root ->
            root.walkTopDown().forEach { f ->
                if (f.isFile && f.name == "scanner_root") {
                    val abi = f.parentFile?.name
                    if (abi != null && abi in abiList) {
                        val prev = newest[abi]
                        if (prev == null || f.lastModified() > prev.lastModified()) newest[abi] = f
                    }
                }
            }
        }

        if (newest.isEmpty()) {
            println("搜索过的目录: ${searchRoots.joinToString { it.absolutePath }}")
            throw GradleException(
                "copyScannerRoot 没有找到任何 ABI 的 scanner_root 产物。" +
                "若继续打包，APK 里会是仓库中那份陈旧的预编译二进制，" +
                "对 C++ 的所有修改都不会生效。"
            )
        }

        abiList.forEach { abi ->
            val src = newest[abi]
            if (src == null) {
                println("⚠️ copyScannerRoot: 未找到 $abi 的产物，该 ABI 仍是仓库里的旧二进制")
                return@forEach
            }
            val destDir = file("src/main/assets/native/$abi")
            destDir.mkdirs()
            src.copyTo(File(destDir, "scanner_root"), overwrite = true)
            println("✅ copyScannerRoot: $abi ← ${src.absolutePath} (${src.length()} bytes)")
        }
    }
}

// 把 copyScannerRoot 正确嵌进任务图。
// 原实现只挂在 externalNativeBuildDebug / mergeDebugNativeLibs / buildCMakeDebug 上，
// release 构建根本不会触发，导致 `flutter build apk --release` 打包的是仓库里
// 提交的旧二进制 —— C++ 改了也白改。
afterEvaluate {
    // AGP 8 用的是按 ABI 拆分的 buildCMakeDebug[arm64-v8a] / buildCMakeRelWithDebInfo[...]，
    // 老版本的聚合任务 externalNativeBuild<Variant> 未必存在，两种都匹配。
    val nativeTasks = tasks.names.filter {
        it.startsWith("buildCMake") || it.startsWith("externalNativeBuild")
    }
    println("copyScannerRoot 将等待 ${nativeTasks.size} 个 CMake 任务: $nativeTasks")

    tasks.named("copyScannerRoot").configure {
        // 用 dependsOn 而不是 mustRunAfter：后者在目标任务不在执行图里时不产生任何约束，
        // 会让复制动作跑在 CMake 之前，扫描到空目录。
        dependsOn(*nativeTasks.toTypedArray())
    }

    listOf("Debug", "Release", "Profile").forEach { variant ->
        val mergeTask = tasks.findByName("merge${variant}Assets") ?: return@forEach
        mergeTask.dependsOn("copyScannerRoot")
        println("✅ merge${variant}Assets 已串上 copyScannerRoot")
    }
}
