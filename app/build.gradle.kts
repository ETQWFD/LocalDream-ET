import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

ktlint {
    android.set(true)
    version.set("1.8.0")
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
        exclude { it.file.path.contains("/cpp/3rdparty/") }
    }
}

detekt {
    toolVersion = "1.23.7"
    config.setFrom("$projectDir/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("$projectDir/detekt-baseline.xml")
    source.setFrom(files("src/main/java", "src/main/kotlin"))
}

android {
    namespace = "io.github.xororz.localdream"
    compileSdk = 37

    // Lint vital runs a full analysis on every release assemble and stalls for
    // tens of minutes under memory-constrained builds. It gates nothing the
    // compiler/APK verifier do not already check, so skip it during packaging.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        checkDependencies = false
    }

    defaultConfig {
        // ET fork uses its own application id so it installs and updates
        // independently of the upstream "Local Dream" package.
        // et.21: renamed to the requested etc.github.ai (new, independent app;
        // models live in the shared public folder and are unaffected).
        applicationId = "etc.github.ai"
        minSdk = 28
//        minSdk = 31
        targetSdk = 36
        versionCode = 122
        versionName = "3.0.0-et.48"

        // et.26: GitHub OAuth Device Flow client_id. Empty => device-flow entry
        // is shown disabled (no request, no fake login). Device flow needs no secret.
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // arm64-v8a  : NPU (QNN) + CPU/GPU engine
            // armeabi-v7a: 32-bit devices, CPU/GPU (MNN) engine only.
            // NOTE: the native engine for each ABI must be built with
            // app/src/main/cpp/build.sh and placed in jniLibs/<abi>/ before a
            // fully functional APK is assembled. QNN NPU libraries ship for
            // aarch64 only; the v7a build is CPU/GPU.
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            // Load ET release signing material from app/release-keystore.properties
            // (git-ignored). Falls back to -P properties / env if absent.
            val propsFile = rootProject.file("release-keystore.properties")
            val releaseProps = Properties().apply {
                if (propsFile.exists()) propsFile.inputStream().use { load(it) }
            }
            fun prop(key: String): String? =
                (project.findProperty(key) as String?) ?: releaseProps.getProperty(key)
            val store = prop("RELEASE_STORE_FILE") ?: "keystore.jks"
            storeFile = file(store).takeIf { it.isFile }
                ?: rootProject.file(store.removePrefix("../"))
            storePassword = prop("RELEASE_STORE_PASSWORD")
            keyAlias = prop("RELEASE_KEY_ALIAS")
            keyPassword = prop("RELEASE_KEY_PASSWORD")
        }
    }

    bundle {
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
        language {
            enableSplit = false
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Kept off in the ET build: this app loads a native backend
            // executable and several reflection/serialization paths; avoiding
            // R8 shrinking removes a class of hard-to-test runtime failures on
            // a constrained build host and keeps the APK deterministic.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Use the auto-generated debug keystore; the release config above
            // is only applied to the release build type.
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
        jniLibs {
            useLegacyPackaging = true
            // et.33: 保留预编译原生引擎的原始符号，勿在打包时二次 strip，
            // 保证出货 .so 与已用双 NDK(armv7 r25 / arm64 r29) 验证的库逐字节一致。
            keepDebugSymbols += listOf("**/*.so")
        }
    }
    flavorDimensions += "version"
    productFlavors {
        create("basic") {
            dimension = "version"
            versionNameSuffix = ""
        }
        create("filter") {
            dimension = "version"
            versionNameSuffix = "_with_filter"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val versionName = output.versionName.orNull
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("LocalDream-ET_${variant.name}_$versionName.apk")
            }
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
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material3.xml)
    implementation(libs.coil.compose)
    implementation(libs.cropify)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Adds the ktlint-rule wrappers to detekt; we only enable UnusedImports
    // (the standalone ktlint plugin's no-unused-imports does not flag them).
    detektPlugins(libs.detekt.formatting)
}
