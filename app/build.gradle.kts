plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sangege.moqpublisher"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sangege.moqpublisher"
        minSdk = 24
        targetSdk = 37
        versionCode = 32
        versionName = "1.0.31"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // All targets published in the official Android MoQ core artifact.
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            // The local release workflow uses the development key so Android can install
            // updates over the existing sideloaded builds without a separate keystore.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
            isShrinkResources = true
        }
        // Small, optimized sideload build signed like the existing debug APK, allowing an in-place update.
        create("compact") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // Both languages must be installed for instant offline switching, including from an App Bundle.
    bundle {
        language {
            enableSplit = false
        }
    }
    androidResources {
        // Keep both supported languages while omitting unused dependency translations.
        localeFilters += setOf("en", "zh-rTW")
    }
    packaging {
        jniLibs {
            // Compress support libraries; the MoQ core is downloaded separately.
            useLegacyPackaging = true
            // Download the matching official core once into app-private storage.
            excludes += "**/libmoq_ffi.so"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.moq)
    // The updater probes the downloaded native ABI before UniFFI initializes it.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.18")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
