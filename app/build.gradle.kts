import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 排障第一句永远是「电视上装的是哪版」，所以把构建时间编译进 APK，
// 设置页页脚直接显示，不用连电脑查 dumpsys。
val buildStamp: String = SimpleDateFormat("MM-dd HH:mm").format(Date())

android {
    namespace = "com.family.curated"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.curated"
        minSdk = 24
        targetSdk = 34
        // 每个 Wave 发一版：Wave 0 + OPT-03 → 2；OPT-04 → 3；Wave 1 → 4
        versionCode = 4
        versionName = "1.3"
        buildConfigField("String", "BUILD_TIME", "\"$buildStamp\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // 设置页要显示版本与构建时间
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")

    // SMB (SMB2/3)
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 纯逻辑单测（ClockMath 这类不碰 Android API 的计算）
    testImplementation("junit:junit:4.13.2")
}
