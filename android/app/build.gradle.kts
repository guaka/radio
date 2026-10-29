import java.security.KeyStore
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val betaVersionCode = providers.gradleProperty("betaVersionCode").orNull ?: "4"
val betaVersionName = providers.gradleProperty("betaVersionName").orNull ?: "0.4.0"
require(betaVersionCode.toIntOrNull()?.let { it > 0 } == true) {
    "-PbetaVersionCode must be a positive integer."
}
require(betaVersionName.isNotBlank()) { "-PbetaVersionName must not be blank." }

val betaKeystore = providers.environmentVariable("RADIO_BETA_KEYSTORE")
val betaStorePassword = providers.environmentVariable("RADIO_BETA_STORE_PASSWORD")
val betaKeyAlias = providers.environmentVariable("RADIO_BETA_KEY_ALIAS")
val betaKeyPassword = providers.environmentVariable("RADIO_BETA_KEY_PASSWORD")

android {
    namespace = "org.guaka.radio"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.guaka.radio"
        minSdk = 28
        targetSdk = 35
        versionCode = betaVersionCode.toInt()
        versionName = betaVersionName
        buildConfigField("String", "BUILD_TIME", "\"${ZonedDateTime.now(ZoneId.of("Europe/Lisbon")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    signingConfigs {
        create("beta") {
            storeFile = betaKeystore.orNull?.let(::file)
            storePassword = betaStorePassword.orNull
            keyAlias = betaKeyAlias.orNull
            keyPassword = betaKeyPassword.orNull
        }
    }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("beta")
        }
    }
}

gradle.taskGraph.whenReady {
    val requestsRelease = allTasks.any { it.name.endsWith("Release", ignoreCase = true) }
    if (requestsRelease) {
        require(!betaKeystore.orNull.isNullOrBlank()) { "RADIO_BETA_KEYSTORE is required to build a beta APK." }
        require(!betaStorePassword.orNull.isNullOrEmpty()) { "RADIO_BETA_STORE_PASSWORD is required to build a beta APK." }
        require(!betaKeyAlias.orNull.isNullOrBlank()) { "RADIO_BETA_KEY_ALIAS is required to build a beta APK." }
        require(!betaKeyPassword.orNull.isNullOrEmpty()) { "RADIO_BETA_KEY_PASSWORD is required to build a beta APK." }
        require(betaKeystore.orNull?.let(::file)?.isFile == true) { "RADIO_BETA_KEYSTORE must point to an existing keystore file." }
        val expectedFingerprint = rootProject.file("beta-certificate.sha256").readText().trim().lowercase()
        require(expectedFingerprint.matches(Regex("[0-9a-f]{64}"))) { "Invalid public beta certificate fingerprint." }
        val keys = KeyStore.getInstance("JKS")
        file(betaKeystore.get()).inputStream().use { keys.load(it, betaStorePassword.get().toCharArray()) }
        val certificate = requireNotNull(keys.getCertificate(betaKeyAlias.get())) { "Beta signing alias has no certificate." }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(fingerprint == expectedFingerprint) {
            "Beta certificate does not match beta-certificate.sha256. Reuse the established signing identity."
        }
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("com.vitorpamplona.quartz:quartz-android:1.05.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.22.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
