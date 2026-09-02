plugins { id("com.android.application") }
android {
    namespace = "com.sprich.qa.editor"
    compileSdk = 36
    defaultConfig { applicationId = "com.sprich.qa.editor"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1"; manifestPlaceholders["qaTargetPackage"] = providers.gradleProperty("qaTargetPackage").getOrElse("com.sprich.app") }
    buildTypes { debug { isDebuggable = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
