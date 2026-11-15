plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Cheia de semnare vine doar din variabile de mediu (GitHub Secrets), niciodată din repo.
val keystorePath: String? = System.getenv("AK_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "ro.ak.sportshub"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.ak.sportshub"
        minSdk = 23
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.$versionCode"
        // Televizoarele și boxurile sunt ARM; fără x86 APK-ul rămâne mic (decodorul FFmpeg are biblioteci native).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("AK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AK_KEY_ALIAS")
                keyPassword = System.getenv("AK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 (fără obfuscare, vezi proguard-rules.pro): Compose și listele sunt mult mai fluide decât fără.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Fără secrete (de exemplu local) se semnează cu cheia de debug, ca APK-ul să fie instalabil.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    testOptions {
        unitTests {
            // Capturile de ecran (Robolectric + Roborazzi) au nevoie de resursele aplicației.
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("roborazzi.test.record", "true")
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                test.maxHeapSize = "2g"
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:playlist"))
    implementation(project(":core:epg"))
    implementation(project(":core:sports"))
    implementation(project(":core:database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)
    // Profilele de pornire ale bibliotecilor Compose se compilează și la instalarea APK-ului direct (fără Play).
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg)
    implementation(libs.zxing.core)

    // Capturile de ecran din CI (workflow-ul „Capturi”): doar în teste, nu în APK.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
