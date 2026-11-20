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
val robolectricSdk by configurations.creating
val prepareRobolectricSdk by tasks.registering(Copy::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
android {
    namespace = "com.resonance.app"
    compileSdk = 35
    defaultConfig {
        applicationId = if (previewBuild) "com.resonance.app.preview" else "com.resonance.app"
        manifestPlaceholders["appLabel"] = if (previewBuild) "Resonance 预览版" else "Resonance"
        manifestPlaceholders["listenerLabel"] = if (previewBuild) "Resonance 预览版媒体监听" else "Resonance 媒体监听"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "0.15.10"
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
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("test").resources.srcDir(rootProject.file("test-fixtures"))
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.dependsOn(prepareRobolectricSdk)
        it.maxHeapSize = "1536m"
        it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
        it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    }
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
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add(robolectricSdk.name, "org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
