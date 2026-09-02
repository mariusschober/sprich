import java.util.Properties
import java.security.MessageDigest

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
    val gitHash = try { providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim() } catch (_: Exception) { "unknown" }
    val explicitCode = (project.findProperty("sprichVersionCode") as? String)?.toIntOrNull()
        ?: System.getenv("SPRICH_VERSION_CODE")?.toIntOrNull()
    val explicitName = (project.findProperty("sprichVersionName") as? String)
        ?: System.getenv("SPRICH_VERSION_NAME")
    val validateReleaseVersion = tasks.register("validateReleaseVersion") {
        doLast {
            require(explicitCode != null && explicitCode in 1..2_100_000_000 && !explicitName.isNullOrBlank()) {
                "Release packaging requires sprichVersionCode and sprichVersionName (or SPRICH_VERSION_CODE/NAME)."
            }
        }
    }
    tasks.configureEach {
        if (name in setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle")) dependsOn(validateReleaseVersion)
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
    val signingProperties = Properties().apply {
        val source = rootProject.file("keystore.properties")
        if (source.isFile) source.inputStream().use { load(it) }
    }
    fun signingValue(property: String, env: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(property)?.takeIf { it.isNotBlank() }
    val signingValues = listOf(
        signingValue("storeFile", "SPRICH_KEYSTORE_FILE"), signingValue("storePassword", "SPRICH_KEYSTORE_PASSWORD"),
        signingValue("keyAlias", "SPRICH_KEY_ALIAS"), signingValue("keyPassword", "SPRICH_KEY_PASSWORD"),
    )
    require(signingValues.all { it == null } || signingValues.all { it != null }) { "Release signing configuration is incomplete." }
    val hasReleaseSigner = signingValues.all { it != null }
    signingConfigs {
        if (hasReleaseSigner) create("release") {
            storeFile = rootProject.file(signingValues[0]!!)
            require(storeFile!!.isFile) { "Release keystore file does not exist." }
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
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
            if (hasReleaseSigner) signingConfig = signingConfigs.getByName("release")
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
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

    ndkVersion = "27.0.12077973"

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

    // Speech recognition only; reproducible build and provenance in native/README.md.
    implementation(files("libs/sherpa-onnx-1.13.6-asr-arm64.aar"))

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

// Auditable coordinates for the artifacts actually resolved by this release build.
tasks.register("writeReleaseDependencyInventory") {
    doLast {
        val output = layout.buildDirectory.file("reports/release-runtime-dependencies.tsv").get().asFile
        output.parentFile.mkdirs()
        output.writeText(configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }.joinToString("\n") {
                val digest = MessageDigest.getInstance("SHA-256").digest(it.file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
                "${it.moduleVersion.id}\t${it.file.name}\t$digest"
            } + "\n")
    }
}
