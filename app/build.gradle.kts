import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * 密钥与签名信息一律从 local.properties / Gradle 属性 / 环境变量读取，绝不写进版本库。
 * 读取优先级：local.properties → -P 参数 → 环境变量。
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String): String? =
    localProperties.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: System.getenv(key)

val releaseStorePath: String? = secret("NETSCOPE_STORE_FILE")
val hasReleaseKeystore: Boolean = !releaseStorePath.isNullOrBlank() && file(releaseStorePath).exists()
fun buildConfigString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val backendUrl = secret("NETSCOPE_BACKEND_URL") ?: "http://127.0.0.1:8787/v1/chat/completions"
val modelId = secret("NETSCOPE_MODEL_ID") ?: "not-configured"

android {
    namespace = "com.netscope"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.netscope"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase0"
        buildConfigField("String", "NETSCOPE_BACKEND_URL", buildConfigString(backendUrl))
        buildConfigField("String", "NETSCOPE_MODEL_ID", buildConfigString(modelId))
    }

    // 只有本地确实存在 keystore 时才注册 release 签名配置。
    
    
    // 一旦 keystore 就位，重新构建即自动切换到正式签名，无需改代码。
    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = secret("NETSCOPE_STORE_PASSWORD")
                keyAlias = secret("NETSCOPE_KEY_ALIAS")
                keyPassword = secret("NETSCOPE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 加后缀是为了让 debug 与 release 能同时装在一台机器上。
            // 否则两者签名不同，覆盖安装会直接报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
            
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
