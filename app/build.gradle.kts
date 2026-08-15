plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.io.File
import java.util.Properties

val nativeJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val uniffiKotlinDir = layout.buildDirectory.dir("generated/source/uniffi/main/kotlin")

// Release signing (ROADMAP 6.4): secret-driven only. Values come from the
// MINDSIGN_* environment (CI secrets) or the same keys in
// ~/.gradle/gradle.properties (maintainer machines). The keystore itself is
// never committed and never echoed by Gradle/CI. When no signing key is
// configured the release buildType falls back to the debug certificate,
// exactly like today, so local `assembleRelease` runs stay unblocked on
// hosts without a keystore.
val mindSign: Map<String, String?> = run {
    val props = Properties()
    val propsFile = File(System.getProperty("user.home"), ".gradle/gradle.properties")
    if (propsFile.isFile) propsFile.inputStream().use { props.load(it) }
    listOf(
        "MINDSIGN_STORE_FILE",
        "MINDSIGN_STORE_PASSWORD",
        "MINDSIGN_KEY_ALIAS",
        "MINDSIGN_KEY_PASSWORD",
    ).associateWith { name -> System.getenv(name) ?: props.getProperty(name) }
}
val releaseSigningConfigured = mindSign.values.all { !it.isNullOrBlank() }

val buildRustAndroid by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds MindChat's Rust cdylib for Android ABIs."
    workingDir = rootProject.projectDir
    commandLine(
        "sh",
        "scripts/build-rust-android.sh",
        nativeJniLibsDir.get().asFile.absolutePath,
    )
    inputs.files(
        rootProject.file("Cargo.toml"),
        rootProject.file("Cargo.lock"),
        rootProject.file("rust-toolchain.toml"),
        rootProject.fileTree(rootProject.file("crates")),
    )
    outputs.dir(nativeJniLibsDir)
    // Local hosts without cargo-ndk/NDK reuse the previously staged jniLibs
    // (CONTRIBUTING "Development setup"). CI always has the toolchain, so the
    // authoritative native assembly still runs there; the script keeps its
    // hard failure for anyone invoking it directly without the toolchain.
    onlyIf {
        val toolchainOk = try {
            ProcessBuilder(
                "sh", "-c",
                "command -v cargo-ndk >/dev/null 2>&1 || cargo ndk --version >/dev/null 2>&1",
            ).start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
        if (!toolchainOk) {
            logger.warn(
                "cargo-ndk/NDK not available; skipping native assembly and " +
                    "reusing staged jniLibs. CI performs the authoritative build.",
            )
        }
        toolchainOk
    }
}

val generateUniffiKotlin by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates Kotlin DTO bindings from the Rust UniFFI contract."
    workingDir = rootProject.projectDir
    commandLine(
        "sh",
        "scripts/generate-uniffi-kotlin.sh",
        uniffiKotlinDir.get().asFile.absolutePath,
    )
    inputs.files(
        rootProject.file("Cargo.toml"),
        rootProject.file("Cargo.lock"),
        rootProject.file("rust-toolchain.toml"),
        rootProject.fileTree(rootProject.file("crates")),
    )
    outputs.dir(uniffiKotlinDir)
}

android {
    namespace = "com.mindchat.app"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.mindchat.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Only the ABIs MindChat ships (ROADMAP 6.4). JNA's AAR carries
        // legacy mips/x86 jniLibs; filtering them here keeps them out of
        // every variant (debug included), not just the release APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(mindSign["MINDSIGN_STORE_FILE"]!!)
                storePassword = mindSign["MINDSIGN_STORE_PASSWORD"]
                keyAlias = mindSign["MINDSIGN_KEY_ALIAS"]
                keyPassword = mindSign["MINDSIGN_KEY_PASSWORD"]
                // v2-only signing (ROADMAP 6.4): apksigner emits no v1 JAR
                // signature. minSdk 26 never needs v1 and the smaller
                // signature surface is easier to audit.
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 with resource shrinking (ROADMAP 6.4); full mode is
            // enabled in gradle.properties. Debug stays minify-off.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // MINDSIGN_* absent: fall back to the debug certificate (ROADMAP
            // 6.4 "debug-cert signing continues"), so local release assembly
            // stays verifiable and unblocked. CI requires the release key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Per-ABI APKs plus a universal one (ROADMAP 6.4). The universal APK is
    // the single artifact for distribution; per-ABI APKs feed the size
    // budget gates and the emulator smoke job in CI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
    sourceSets.getByName("main").apply {
        jniLibs.srcDir(nativeJniLibsDir)
        java.srcDir(uniffiKotlinDir)
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.jna) {
        artifact {
            name = "jna"
            type = "aar"
        }
    }

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

tasks.configureEach {
    if (name.endsWith("Kotlin") && name != "generateUniffiKotlin") {
        dependsOn(generateUniffiKotlin)
    }
}

tasks.named("preBuild") {
    dependsOn(buildRustAndroid)
}
