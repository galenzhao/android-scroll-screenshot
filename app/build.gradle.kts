import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.galenzhao.scrollshot"
    compileSdk = 36

    defaultConfig {
        // 自动根据当前时间生成版本号
        val now = LocalDateTime.now(ZoneOffset.UTC)

        // 作为展示用的版本名：yyyyMMddHHmm（年月日时分）
        val versionNameFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        versionName = now.format(versionNameFormatter)

        // 作为内部用的 versionCode：以 2024-01-01 00:00 为基准的“分钟数”
        // 保证是递增的 Int，不会超过 Android 的上限
        val base = LocalDateTime.of(2024, 1, 1, 0, 0)
        val minutesSinceBase =
            ((now.toEpochSecond(ZoneOffset.UTC) - base.toEpochSecond(ZoneOffset.UTC)) / 60).toInt()
        applicationId = "com.galenzhao.scrollshot"
        minSdk = 30
        targetSdk = 36
        versionCode = minutesSinceBase
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
