import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.programmieramt.inklauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.programmieramt.inklauncher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Must be the same key as Overwatcher: its calendar data permission is signature-protected.
            signingConfig = signingConfigs.getByName("debug")
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
        jniLibs {
            pickFirsts += listOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // Applies the baseline profiles shipped with Compose, so release builds start fast without JIT warm-up.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Onyx pen SDK, same versions as Overwatcher: fast raw drawing of the pen on the e-ink panel.
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.2.1")
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.1.3")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.0.4") {
        exclude(group = "com.onyx.android.sdk", module = "onyxsdk-geometry")
    }
    // The pen SDK brings an old fragment version, too old for the Activity Result API.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // The pen SDK uses hidden APIs (see Overwatcher / toolsboox issue 305).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
