import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vibetoon"
    compileSdk = 35
    ndkVersion = "27.0.12077973" // точно как в SDK Manager -> NDK (Side by side)

    defaultConfig {
        applicationId = "com.vibetoon"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // пакет 14: короткий хеш коммита сборки (в CI - GITHUB_SHA) - виден в окне экспорта, чтобы проверять, какая сборка установлена
        buildConfigField("String", "BUILD_ID", "\"" + (System.getenv("GITHUB_SHA")?.take(7) ?: "local") + "\"")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
        // Собираем только эти ABI: реальные телефоны и эмулятор x86_64
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Публичный тестовый ключ только для debug. Никогда не использовать для релизов.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug-signing/vibetoon-debug.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "vibetoon-debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
}

// По запросу (задача exportDebugApk) debug-APK копируется в Downloads\VibeToon\apk с именем VibeToon-<дата-время>-debug.apk,
// а документы (docs/) - в Downloads\VibeToon (README, CHANGELOG, architecture, testing, commits).
val exportRoot = File(System.getProperty("user.home"), "Downloads/VibeToon")
val exportDocs = tasks.register<Copy>("exportDocs") {
    from(rootProject.file("docs"))
    into(exportRoot)
}
val exportDebugApk = tasks.register<Copy>("exportDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("app-debug.apk") }
    into(File(exportRoot, "apk"))
    rename { "VibeToon-" + SimpleDateFormat("yyyyMMdd-HHmm").format(Date()) + "-debug.apk" }
    finalizedBy(exportDocs)
}
// APK в папку копируется только по запросу: gradlew assembleDebug exportDebugApk
