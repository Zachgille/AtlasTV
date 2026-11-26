import java.util.Properties
import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun readDebugPlaylistUrl(): String {
    val secretsFile = rootProject.file("debug-secrets.properties")
    if (!secretsFile.exists()) return ""

    val properties = Properties()
    secretsFile.inputStream().use { input -> properties.load(input) }
    return properties.getProperty("ATLASTV_DEBUG_PLAYLIST_URL", "")
}

fun readDebugSecretProperty(name: String): String {
    val secretsFile = rootProject.file("debug-secrets.properties")
    if (!secretsFile.exists()) return ""

    val properties = Properties()
    secretsFile.inputStream().use { input -> properties.load(input) }
    return properties.getProperty(name, "")
}

// Existing personal-use snapshot; raw datasets/account files are never copied into an APK.
val discoveryManifest = file("discovery-snapshot.properties")
val discoverySnapshot = Properties().apply { discoveryManifest.inputStream().use { load(it) } }
val discoveryAssets = when (discoverySnapshot.getProperty("assetLayout", "legacy")) {
    "legacy" -> rootProject.file(".local-discovery/assets")
    "immutable-v1" -> {
        val digest = discoverySnapshot.getProperty("sha256")
        check(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid discovery manifest digest." }
        rootProject.file(".local-discovery/snapshots/$digest")
    }
    else -> error("Unknown discovery asset layout.")
}
val discoveryAssetNames = listOf("imdb-discovery.tsv.bin", "imdb-discovery-receipt.json", "IMDb-NOTICE.txt")
val generatedDiscoveryAssets = layout.buildDirectory.dir("generated/discovery-assets")
val verifyDiscoveryAssets by tasks.registering {
    inputs.file(discoveryManifest)
    inputs.files(discoveryAssetNames.map { discoveryAssets.resolve(it) })
    doLast {
        check(discoveryAssetNames.all { discoveryAssets.resolve(it).isFile }) {
            "Missing personal discovery snapshot. Run scripts/prepare-imdb-discovery.py to prepare and activate verified data."
        }
        val snapshot = discoveryAssets.resolve("imdb-discovery.tsv.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        snapshot.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val receipt = JsonSlurper().parse(discoveryAssets.resolve("imdb-discovery-receipt.json")) as Map<*, *>
        check(discoverySnapshot.getProperty("dataset") == receipt["dataset"]) { "Discovery dataset identity does not match its receipt." }
        check(sha256 == discoverySnapshot.getProperty("sha256") && sha256 == receipt["snapshotSha256"]) {
            "Independent discovery snapshot hash does not match its pinned manifest/receipt."
        }
        for ((key, receiptKey) in listOf("bytes" to "snapshotBytes", "records" to "records", "minimumVotes" to "minimumVotes")) {
            check(discoverySnapshot.getProperty(key).toLong() == (receipt[receiptKey] as Number).toLong()) { "Discovery receipt mismatch: $key" }
        }
        check(snapshot.length() == discoverySnapshot.getProperty("bytes").toLong()) { "Discovery snapshot size mismatch." }
        val notice = discoveryAssets.resolve("IMDb-NOTICE.txt").readText()
        check(notice.contains("Information courtesy of IMDb") && notice.contains("Used with permission.")) { "Missing IMDb attribution." }
    }
}
val prepareDiscoveryAssets by tasks.registering(Sync::class) {
    dependsOn(verifyDiscoveryAssets)
    from(discoveryAssets) { include(discoveryAssetNames) }
    into(generatedDiscoveryAssets)
}

android {
    namespace = "com.atlas.atlastv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.atlas.atlastv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "IMDB_DISCOVERY_DATASET", discoverySnapshot.getProperty("dataset").asBuildConfigString())
        buildConfigField("String", "IMDB_DISCOVERY_SHA256", discoverySnapshot.getProperty("sha256").asBuildConfigString())
        buildConfigField("int", "IMDB_DISCOVERY_RECORDS", discoverySnapshot.getProperty("records"))
        buildConfigField("int", "IMDB_DISCOVERY_MINIMUM_VOTES", discoverySnapshot.getProperty("minimumVotes"))
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "ATLASTV_DEBUG_PLAYLIST_URL",
                readDebugPlaylistUrl().asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "ATLASTV_DEBUG_XTREAM_BASE_URL",
                readDebugSecretProperty("ATLASTV_DEBUG_XTREAM_BASE_URL").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "ATLASTV_DEBUG_XTREAM_USERNAME",
                readDebugSecretProperty("ATLASTV_DEBUG_XTREAM_USERNAME").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "ATLASTV_DEBUG_XTREAM_PASSWORD",
                readDebugSecretProperty("ATLASTV_DEBUG_XTREAM_PASSWORD").asBuildConfigString(),
            )
        }

        release {
            isMinifyEnabled = false
            buildConfigField("String", "ATLASTV_DEBUG_PLAYLIST_URL", "".asBuildConfigString())
            buildConfigField("String", "ATLASTV_DEBUG_XTREAM_BASE_URL", "".asBuildConfigString())
            buildConfigField("String", "ATLASTV_DEBUG_XTREAM_USERNAME", "".asBuildConfigString())
            buildConfigField("String", "ATLASTV_DEBUG_XTREAM_PASSWORD", "".asBuildConfigString())
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        // Install over the development app without clearing its library. Normal ART compilation
        // is available, with neither debugger overhead nor embedded development credentials.
        create("preview") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Avoid decompressing code during cold startup on the Onn.
    packaging {
        dex.useLegacyPackaging = false
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("main").assets.srcDir(generatedDiscoveryAssets)
    }
}

tasks.named("preBuild") { dependsOn(prepareDiscoveryAssets) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.05.01"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
