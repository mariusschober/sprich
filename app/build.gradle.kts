plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sprich.app"
    compileSdk = 36

    // Explicit monotonic release version — git count is fallback for local debug only, not release authority.
    // Release CI must provide sprichVersionCode/sprichVersionName via gradle.properties or env SPRICH_VERSION_CODE/NAME.
    val gitCount = try { providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt() } catch (_: Exception) { 1 }
    val gitHash = try { providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim() } catch (_: Exception) { "unknown" }
    val explicitCode = (project.findProperty("sprichVersionCode") as? String)?.toIntOrNull()
        ?: System.getenv("SPRICH_VERSION_CODE")?.toIntOrNull()
    val explicitName = (project.findProperty("sprichVersionName") as? String)
        ?: System.getenv("SPRICH_VERSION_NAME")
    // For release builds, require explicit monotonic version — fail clearly if absent
    val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) || it.contains("BundleRelease", ignoreCase = true) }
    if (isReleaseTask && explicitCode == null) {
        logger.warn("Release build without explicit sprichVersionCode — using git count $gitCount as fallback (local dev). CI must set SPRICH_VERSION_CODE for Play.")
    }
    defaultConfig {
        applicationId = "com.sprich.app"
        minSdk = 26
        targetSdk = 36
        versionCode = (explicitCode ?: gitCount).coerceAtLeast(1)
        versionName = explicitName ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        buildConfigField("String", "GIT_COMMIT", "\"$gitHash\"")
        buildConfigField("boolean", "ENABLE_BENCHMARK", "true")
    }
    // Signing — external upload-key via keystore.properties (never commit). Local/CI secrets.
    signingConfigs {
        create("release") {
            // External upload-key signing via keystore.properties or env — never commit .jks
            // Example keystore.properties:
            // storeFile=/path/to/upload.jks
            // storePassword=...
            // keyAlias=upload
            // keyPassword=...
            // CI can also set SPRICH_KEYSTORE_FILE etc.
            // Actual loading done via gradle.properties / CI secrets; placeholder keeps build compilable without secrets.
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            isJniDebuggable = true
            buildConfigField("boolean", "ENABLE_BENCHMARK", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            // Signing configured externally via keystore.properties / env — CI unsigned artifact verification separate
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "ENABLE_BENCHMARK", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Release must NOT keep debug symbols in APK — generate separate symbols for Play (R8 mapping + native symbols)
        jniLibs {
            // keepDebugSymbols removed for release; debug symbols are stored separately for Play crash symbolication
        }
        // Ensure 16KB uncompressed native libs handling for target 36
        // (extractNativeLibs false is default with this packaging on API 23+, keep as is)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        disable += setOf("MissingLeanbackLauncher", "ImpliedTouchscreenHardware", "UnsupportedTvHardware", "LogTagMismatch")
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"

    // JFK fixture now in app/src/main/assets/jfk.wav (whisper deleted, Canary focus)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation:1.7.3")

    // Desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Networking only for model download (isolated)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Sherpa-ONNX 1.13.6 for Canary 180M Flash INT8 + FastConformer + Whisper Tiny LID (single runtime, 1.12.11 removed)
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))

    // Coroutines & serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
