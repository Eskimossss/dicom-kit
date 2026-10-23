import java.util.Properties
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import java.security.MessageDigest
import groovy.json.JsonOutput

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

fun getGitCommandOutput(vararg args: String): String {
    return try {
        val process = ProcessBuilder("git", *args)
            .directory(rootDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (finished && process.exitValue() == 0 && text.isNotEmpty()) text else "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}

val gitBranch = getGitCommandOutput("rev-parse", "--abbrev-ref", "HEAD")
val rawGitCommit = getGitCommandOutput("rev-parse", "--short", "HEAD")
val gitDirtyStatus = getGitCommandOutput("status", "--porcelain")
val isGitDirty = gitDirtyStatus.isNotEmpty() && gitDirtyStatus != "unknown"
val gitCommitSha = when {
    rawGitCommit == "unknown" -> "unknown"
    isGitDirty -> "$rawGitCommit-dirty"
    else -> rawGitCommit
}

val buildTimestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())

// Opt-in local performance comparison. Never participates in the normal release build.
val journalPerfReleaseEnabled = providers.gradleProperty("journalPerfRelease").orNull == "true"

android {
    namespace = "ru.medeng.dicomtool"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ru.medeng.dicomtool"
        minSdk = 26
        targetSdk = 35
        versionCode = 10600
        versionName = "1.6.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "GIT_COMMIT_SHA", "\"$gitCommitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
        buildConfigField("Boolean", "IS_GIT_DIRTY", "$isGitDirty")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
            }

            val storeFilePath = keystoreProperties.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("KEYSTORE_PATH")
            val storePasswordProp = keystoreProperties.getProperty("RELEASE_STORE_PASSWORD")
                ?: System.getenv("KEYSTORE_PASSWORD")
            val keyAliasProp = keystoreProperties.getProperty("RELEASE_KEY_ALIAS")
                ?: System.getenv("KEY_ALIAS")
            val keyPasswordProp = keystoreProperties.getProperty("RELEASE_KEY_PASSWORD")
                ?: System.getenv("KEY_PASSWORD")

            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        debug {
            // Opt-in isolated emulator package; normal debug/release application IDs are unchanged.
            if (providers.gradleProperty("stage51Isolated").orNull == "true") {
                applicationIdSuffix = ".storagesafetytest"
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
        if (journalPerfReleaseEnabled) {
            create("journalPerfRelease") {
                initWith(getByName("release"))
                isDebuggable = false
                applicationIdSuffix = ".journalperf"
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += "release"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,LICENSE*,NOTICE*}"
        }
    }
}

// Public fingerprint verified against the preserved Dicom-Kit-v1.3.1.apk.
// A different key requires an explicit signing/migration decision, never an automatic fallback.
val releaseCertificateSha256 = "4690b671b00bdda65c9740a27dbd2250dc8ccbdd95797e112c3c4763aa6c1a8d"
(listOf("debug", "release") + if (journalPerfReleaseEnabled) listOf("journalPerfRelease") else emptyList()).forEach { variant ->
    val isJournalPerf = variant == "journalPerfRelease"
    val capitalized = variant.replaceFirstChar { it.uppercaseChar() }
    val assetDirectory = layout.buildDirectory.dir("generated/apkMetadata/$variant/assets")
    android.sourceSets.getByName(variant).assets.srcDir(assetDirectory)
    val metadataTask = tasks.register("generate${capitalized}ApkMetadata") {
        outputs.dir(assetDirectory)
        outputs.upToDateWhen { false }
        doLast {
            val signing = if (isJournalPerf) android.buildTypes.getByName(variant).signingConfig!!
                else android.signingConfigs.getByName(variant)
            val certificate = try {
                val store = signing.storeFile ?: error("missing store")
                require(store.isFile && !signing.storePassword.isNullOrBlank() &&
                    !signing.keyAlias.isNullOrBlank() && !signing.keyPassword.isNullOrBlank())
                val keyStore = KeyStore.getInstance(store, signing.storePassword!!.toCharArray())
                require(keyStore.getKey(signing.keyAlias, signing.keyPassword!!.toCharArray()) != null)
                val cert = keyStore.getCertificate(signing.keyAlias) ?: error("missing certificate")
                val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (variant == "release") {
                    require(digest == releaseCertificateSha256)
                } else {
                    require(digest != releaseCertificateSha256)
                }
                digest
            } catch (_: Exception) {
                throw GradleException("$capitalized signing unavailable or certificate mismatch. Check ignored keystore.properties / signing environment and existing key. No key is created or replaced; expected release certificate: $releaseCertificateSha256")
            }
            val metadata = linkedMapOf(
                "applicationId" to android.defaultConfig.applicationId +
                    (android.buildTypes.getByName(variant).applicationIdSuffix ?: ""),
                "versionName" to android.defaultConfig.versionName,
                "versionCode" to android.defaultConfig.versionCode,
                "buildType" to variant,
                "gitBranch" to gitBranch,
                "gitHead" to getGitCommandOutput("rev-parse", "HEAD"),
                "gitCommit" to gitCommitSha,
                "gitDirty" to isGitDirty,
                "buildTime" to buildTimestamp,
                "certificateSha256" to certificate
            )
            val json = JsonOutput.prettyPrint(JsonOutput.toJson(metadata))
            val directory = assetDirectory.get().asFile.apply { mkdirs() }
            directory.resolve("dicom-kit-build.json").writeText(json)
        }
    }
    // AGP creates the standard debug keystore in validateSigningDebug on a fresh machine.
    // That task already depends on preDebugBuild, so avoid a dependency cycle here.
    if (variant == "debug" || isJournalPerf) {
        metadataTask.configure { dependsOn("validateSigning$capitalized") }
    }
    tasks.matching { it.name == if (variant == "debug" || isJournalPerf) "merge${capitalized}Assets" else "preReleaseBuild" }.configureEach {
        dependsOn(metadataTask)
    }
    // Generated assets are also inputs to lint models and analysis for each variant.
    tasks.matching { it.name.contains(capitalized) && it.name.contains("lint", ignoreCase = true) }.configureEach {
        dependsOn(metadataTask)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // DICOM Stack (dcm4che3: PS3.7 DIMSE, PS3.8 Upper Layer, PS3.10 Media Storage)
    implementation(libs.dcm4che.core)
    implementation(libs.dcm4che.net)
    implementation(libs.dcm4che.dict)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.androidx.ui.tooling)
}
