import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val supportedMoQAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
val splitAbi = providers.gradleProperty("moqAbi").orNull
require(splitAbi == null || splitAbi in supportedMoQAbis) { "moqAbi must be one of $supportedMoQAbis" }
val includedMoQAbis = splitAbi?.let(::setOf) ?: supportedMoQAbis
val offlineCoreAssets = layout.buildDirectory.dir("generated/offlineCore/${splitAbi ?: "universal"}/assets")

val productionStore = providers.environmentVariable("MOQ_PRODUCTION_KEYSTORE").orNull?.takeIf(String::isNotBlank)?.let(::file)
val productionStorePassword = providers.environmentVariable("MOQ_PRODUCTION_STORE_PASSWORD").orNull
val productionAlias = providers.environmentVariable("MOQ_PRODUCTION_KEY_ALIAS").orNull
val productionKeyPassword = providers.environmentVariable("MOQ_PRODUCTION_KEY_PASSWORD").orNull
val hasProductionSigning = productionStore != null &&
    listOf(productionStorePassword, productionAlias, productionKeyPassword).none { it.isNullOrBlank() }

android {
    namespace = "com.sangege.moqpublisher"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sangege.moqpublisher"
        minSdk = 24
        targetSdk = 37
        versionCode = 46
        versionName = "1.0.45"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "GITHUB_APP_UPDATES", "true")
        ndk {
            abiFilters += includedMoQAbis
        }
    }

    signingConfigs {
        create("production") {
            if (hasProductionSigning) {
                storeFile = productionStore
                storePassword = productionStorePassword
                keyAlias = productionAlias
                keyPassword = productionKeyPassword
            }
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
        // Full sideload build. The official FFI core is copied into assets by
        // prepareOfflineCore and installed into the app-private core directory
        // after the same ABI and symbol checks used for downloaded cores.
        create("offline") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("production") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("production")
            buildConfigField("boolean", "GITHUB_APP_UPDATES", "false")
            matchingFallbacks += "release"
        }
    }
    sourceSets.getByName("offline") {
        assets.srcDir(offlineCoreAssets.get().asFile)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation("com.google.zxing:core:3.5.3")
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

val prepareOfflineCore = tasks.register("prepareOfflineCore") {
    notCompatibleWithConfigurationCache("Resolves the selected Android AAR during offline asset preparation")
    outputs.dir(offlineCoreAssets)
    inputs.property("abis", includedMoQAbis.sorted())
    doLast {
        val output = offlineCoreAssets.get().asFile
        output.deleteRecursively()
        check(output.mkdirs()) { "Cannot create offline core assets directory" }
        val configuration = configurations.findByName("offlineRuntimeClasspath")
            ?: configurations.findByName("releaseRuntimeClasspath")
            ?: error("Offline runtime classpath is unavailable")
        val artifact = configuration.resolvedConfiguration.resolvedArtifacts
            .firstOrNull { it.file.name == "moq-ffi-release.aar" }
            ?: error("The official moq-ffi Android AAR is not present")
        ZipFile(artifact.file).use { archive ->
            archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("jni/") && it.name.endsWith("/libmoq_ffi.so") }
                .forEach { entry ->
                    val parts = entry.name.split('/')
                    check(parts.size == 3) { "Unexpected native core path ${entry.name}" }
                    if (parts[1] !in includedMoQAbis) return@forEach
                    val target = output.resolve("moq-core-offline/${parts[1]}/libmoq_ffi.so")
                    target.parentFile.mkdirs()
                    archive.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                    val digest = MessageDigest.getInstance("SHA-256")
                    target.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    target.resolveSibling("libmoq_ffi.so.sha256").writeText(
                        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                    )
                }
        }
        val packagedAbis = output.resolve("moq-core-offline").listFiles().orEmpty().map { it.name }.toSet()
        check(packagedAbis == includedMoQAbis) {
            "The official AAR lacks the requested offline MoQ ABIs: ${includedMoQAbis - packagedAbis}"
        }
    }
}

tasks.matching { it.name == "preOfflineBuild" }.configureEach { dependsOn(prepareOfflineCore) }

val requireProductionSigning = tasks.register("requireProductionSigning") {
    notCompatibleWithConfigurationCache("The production signing credentials are local to this build")
    doLast {
        check(hasProductionSigning && productionStore?.isFile == true) {
            "Production signing requires MOQ_PRODUCTION_KEYSTORE, MOQ_PRODUCTION_STORE_PASSWORD, " +
                "MOQ_PRODUCTION_KEY_ALIAS, and MOQ_PRODUCTION_KEY_PASSWORD."
        }
        check(productionStore.canonicalFile != android.signingConfigs.getByName("debug").storeFile?.canonicalFile) {
            "Use a production keystore separate from the installed development APK key."
        }
    }
}
tasks.matching { it.name == "preProductionBuild" }.configureEach { dependsOn(requireProductionSigning) }
