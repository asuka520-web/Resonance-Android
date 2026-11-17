import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val previewBuild = providers.gradleProperty("resonancePreview").map { it.toBoolean() }.getOrElse(false)
val cloudDebugKeystore = providers.environmentVariable("RESONANCE_DEBUG_KEYSTORE").orNull
val sharedSigningFile = cloudDebugKeystore?.let { file(it) }
    ?: rootProject.file(".ci-signing/debug.keystore")
android {
    namespace = "com.resonance.app"
    compileSdk = 35
    defaultConfig {
        applicationId = if (previewBuild) "com.resonance.app.preview" else "com.resonance.app"
        manifestPlaceholders["appLabel"] = if (previewBuild) "Resonance 预览版" else "Resonance"
        manifestPlaceholders["listenerLabel"] = if (previewBuild) "Resonance 预览版媒体监听" else "Resonance 媒体监听"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.2"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = sharedSigningFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("test").resources.srcDir(rootProject.file("test-fixtures"))
}
tasks.configureEach {
    if (name == "validateSigningDebug" || name == "validateSigningRelease") {
        doFirst {
            check(sharedSigningFile.isFile) {
                "Shared signing key missing. Restore the existing key; do not generate a replacement."
            }
            val keystore = KeyStore.getInstance("JKS")
            sharedSigningFile.inputStream().use { keystore.load(it, "android".toCharArray()) }
            val certificate = checkNotNull(keystore.getCertificate("androiddebugkey"))
            val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val expected = rootProject.file("signing-certificate.sha256").readText().trim()
            check(actual == expected) { "Signing certificate mismatch. Refusing to create an incompatible APK." }
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
