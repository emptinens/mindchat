plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val nativeJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val uniffiKotlinDir = layout.buildDirectory.dir("generated/source/uniffi/main/kotlin")

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
        versionCode = 4
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
