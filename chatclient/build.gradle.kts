import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.1.1 has built-in Kotlin support; org.jetbrains.kotlin.android is
    // removed and would hard-fail. Only the Compose compiler plugin is needed.
    alias(libs.plugins.kotlin.compose)
}

// chatclient: independent "chat-style local image client" for the ET server.
// Pure Kotlin + Compose, no native code, no flavors. It only consumes the
// HTTP API described in server-dist/SERVER-API.md and installs side by side
// with :app under a different applicationId.
android {
    namespace = "etc.github.ai.chat"
    compileSdk = 37

    defaultConfig {
        applicationId = "etc.github.ai.chat"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2-et"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        create("release") {
            // Same ET release signing material as :app (root release-keystore.properties),
            // loaded without printing anything.
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.material3.xml)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
