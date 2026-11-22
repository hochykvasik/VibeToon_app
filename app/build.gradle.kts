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

    // Подпись debug-сборки. Если задан VT_DEBUG_KEYSTORE (CI: секрет DEBUG_KEYSTORE_BASE64), подписываем общим
    // ключом проекта, чтобы debug-APK из CI ставился поверх предыдущего на планшете. Иначе - стандартный ~/.android/debug.keystore.
    signingConfigs {
        getByName("debug") {
            val ks = System.getenv("VT_DEBUG_KEYSTORE")?.let { file(it) }
            if (ks != null && ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("VT_DEBUG_STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("VT_DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("VT_DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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
    buildFeatures { compose = true }
}

// Имя APK: vibetoon-debug.apk / vibetoon-release.apk (CI выкладывает vibetoon-debug.apk артефактом)
base { archivesName.set("vibetoon") }

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
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("vibetoon-debug.apk") }
    into(File(exportRoot, "apk"))
    rename { "VibeToon-" + SimpleDateFormat("yyyyMMdd-HHmm").format(Date()) + "-debug.apk" }
    finalizedBy(exportDocs)
}
// APK в папку копируется только по запросу: gradlew assembleDebug exportDebugApk
