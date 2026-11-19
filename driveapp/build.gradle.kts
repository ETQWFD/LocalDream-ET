import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Nightmare 云盘：独立小 App，用主 App（Nightmare Road-ET）注册的邮箱账号登录，
// 读取私密仓库 ETQWFD/NightmareRoad-ET-Private 里 users/<邮箱>/images/ 的图片，
// 支持预览原图与下载到本机相册，不提供删除。
val driveToken: String = let {
    val p = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
    p.getProperty("gallery.token", "")
}

android {
    namespace = "etc.github.drive"
    compileSdk = 37

    defaultConfig {
        applicationId = "etc.github.drive"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3-et"
        buildConfigField("String", "GALLERY_TOKEN", "\"$driveToken\"")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        create("release") {
            // 与 :app / :chatclient 用同一套 ET 发布签名材料。
            val propsFile = rootProject.file("release-keystore.properties")
            val releaseProps = Properties().apply {
                if (propsFile.exists()) propsFile.inputStream().use { load(it) }
            }
            fun prop(key: String): String? =
                (project.findProperty(key) as String?) ?: releaseProps.getProperty(key)
            val store = prop("RELEASE_STORE_FILE") ?: "keystore.jks"
            storeFile = file(store).takeIf { it.isFile } ?: rootProject.file(store.removePrefix("../"))
            storePassword = prop("RELEASE_STORE_PASSWORD")
            keyAlias = prop("RELEASE_KEY_ALIAS")
            keyPassword = prop("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.okhttp)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
