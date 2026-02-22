plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

import java.text.SimpleDateFormat
import java.util.Date

android {
    namespace = "com.carwash.carpayment"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.carwash.carpayment"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1-debug"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // USDK SDK: 配置 native 库路径
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        
        // 构建自证标记：生成 BuildConfig 字段
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true  // 启用 BuildConfig 生成
    }
    
    // KSP 配置（替代 Kapt）
    // KSP 不需要额外配置，会自动处理
    
    // USDK SDK: 配置 native 库路径
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs", "../DOC/USDK_Android@1.0.4_2024112601/USDK_Android@1.0.4_2024112601/SDK/libs")
        }
    }
}

dependencies {
    // Kotlin 标准库 - 确保版本一致
    implementation(libs.kotlin.stdlib)
    
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // ProcessLifecycleOwner (for app lifecycle monitoring)
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    
    // Coroutines - 使用版本目录统一管理
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // DataStore
    implementation(libs.androidx.datastore.preferences)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Room - 确保版本一致，使用 KSP 替代 Kapt
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Guava - 添加以解决可能的依赖冲突
    implementation(libs.guava)
    
    // Retrofit for REST API
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    
    // USDK SDK
    implementation(files("../DOC/USDK_Android@1.0.4_2024112601/USDK_Android@1.0.4_2024112601/SDK/libs/usdk_v2024112602.jar"))
    
    // CSN Printer SDK
    implementation(files("../DOC/AndroidSDK_210128/AndroidSDK_210128/03_Jar/CSNPrinterSDK/jar/CSNPrinterSDK.jar"))
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    
    // 强制使用 Kotlin 2.0.21 以确保版本一致
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21")
        }
    }
}