plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.chinut.bawantv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chinut.bawantv"

        // ---------- 最低版本：降到 Android 6.0（API 23）----------
        //
        // 原来是 24（Android 7.0），结果真机电视报「解析包时出现问题」——
        // 这是**系统版本太低**的典型症状（安装器直接拒绝解析）。
        // 国产电视/盒子的系统比手机落后得多，大量机器停在 Android 6.0/7.0。
        //
        // 为什么是 23 而不是更低：
        //   androidx.compose.animation:animation-core 硬性要求 minSdk >= 23。
        //   硬覆盖（tools:overrideLibrary）会让动画在低版本上运行时崩溃，
        //   不如老老实实抬到 23 —— 能覆盖 Android 6.0 及以上，比 24 广一档。
        //
        // 代价：所有 API 24+ 的调用都要做版本判断（编译器会逐个报出来），
        // 见各处 `Build.VERSION.SDK_INT` 判断。
        minSdk = 23

        targetSdk = 36
        // v1.0.15：修直播黑屏 —— 只有在硬解**真的出画面**后才隐藏网页，
        //          12 秒内没出画面就退回网页保底（上一版一起播就隐藏，失败即全黑）
        versionCode = 83
        versionName = "1.7.2"
    }

    // TV 端只需要这几种 ABI（盒子/电视基本都是 arm，模拟器是 x86_64）
    splits {
        abi {
            isEnable = false
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            // 便于自动更新：使用固定签名（自用/学习项目）
            storeFile = file("bawan.jks")
            storePassword = "bawan123"
            keyAlias = "bawan"
            keyPassword = "bawan123"

            // ---------- 签名方案：三个都要开 ----------
            //
            // 踩过的坑：不显式配置时 AGP 高版本**默认只签 v2**，
            // 结果电视上提示"安装包异常/解析失败"。
            //
            // 原因是不同 Android 版本认的方案不一样：
            //   · Android 6 及更早 / 部分国产电视盒子：**只认 v1**（JAR 签名）
            //   · Android 7~8：v2
            //   · Android 9+：v3
            // 电视系统的版本分布比手机乱得多，所以三个全开最保险 ——
            // 安装包会大几十 KB，换来的是"哪台电视都能装"。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Android TV 官方组件
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.leanback)

    // 播放器
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)

    // 网络 / 协程 / 图片 / 二维码
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
