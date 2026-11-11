import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 排障第一句永远是「电视上装的是哪版」，所以把构建时间编译进 APK，
// 设置页页脚直接显示，不用连电脑查 dumpsys。
val buildStamp: String = SimpleDateFormat("MM-dd HH:mm").format(Date())

// 首次启动的默认 NAS 账号。**真实账号密码不进 Git**：
//   1. `local.properties`（已 gitignore）里的 nas.user / nas.pass；
//   2. 环境变量 NAS_USER / NAS_PASS（CI 或临时构建）。
// 两边都没有时是空串 —— 仓库 clone 下来照样能编译，只是首次启动要在设置页手填。
// 之前这份是 gitignore 掉的 `LocalSecrets.kt`，导致「只有作者本机能编过」，
// GitHub Actions 第一次跑就在编译阶段红了。
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, env: String): String =
    (localProps.getProperty(key) ?: System.getenv(env) ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val nasUser: String = secret("nas.user", "NAS_USER")
val nasPass: String = secret("nas.pass", "NAS_PASS")

android {
    namespace = "com.family.curated"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.curated"
        minSdk = 24
        targetSdk = 34
        // 每个 Wave 发一版：Wave 0 + OPT-03 → 2；OPT-04 → 3；Wave 1 → 4；Wave 2 → 5；
        // Wave 3 → 6 / 7；Wave 4：OPT-17 异常落盘 → 8，OPT-16 文案收口 → 9，OPT-18 设置页拆分 → 10；
        // 实机修复：维护分组两个对话框被 message 挤没 + 家长选项菜单末尾项够不到 → 11
        // PR #1 真缺口移植：SHA-256 清单指纹、握手期可强关、SMB 线程池有界 → 12
        // 实机发现：NAS 不可达时记录备份退化成每秒重连一次 → 13
        // 实机发现：远端日志超 400 KB 时从头部读、截断处留残句 → 14
        // 实机发现：清掉 watch_ 后 daymax 顶着原值，冷启动补用量被误判成「今天已有用量」→ 15
        versionCode = 15
        versionName = "1.9.5"
        buildConfigField("String", "BUILD_TIME", "\"$buildStamp\"")
        // 默认 NAS 账号：来自 local.properties / 环境变量，缺失时是空串（见文件上方说明）
        buildConfigField("String", "NAS_USER", "\"$nasUser\"")
        buildConfigField("String", "NAS_PASS", "\"$nasPass\"")

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
