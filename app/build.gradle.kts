import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---- Rust engine ---------------------------------------------------------------------------
// Builds rust/ for every ABI with cargo-ndk and drops the .so files into src/main/jniLibs.
// Requirements: rustup targets, cargo-ndk, ANDROID_NDK_HOME. Pass -PskipRust to reuse existing libs.
val rustDir = rootProject.layout.projectDirectory.dir("rust").asFile
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val buildRust = tasks.register<Exec>("buildRust") {
    group = "rust"
    description = "Compiles the GitPush native engine with cargo-ndk"
    workingDir = rustDir
    val cmd = mutableListOf("cargo", "ndk")
    rustAbis.forEach { cmd.addAll(listOf("-t", it)) }
    cmd.addAll(listOf("--platform", "26", "-o", jniLibsDir.absolutePath, "build", "--release"))
    commandLine(cmd)
    inputs.dir(File(rustDir, "src"))
    inputs.file(File(rustDir, "Cargo.toml"))
    outputs.dir(jniLibsDir)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    if (!project.hasProperty("skipRust")) dependsOn(buildRust)
}

// ---- Android -------------------------------------------------------------------------------
fun secret(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.gitpush.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gitpush.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        ndk { abiFilters += rustAbis }
        buildConfigField("String", "DEFAULT_CLIENT_ID", "\"${secret("GP_CLIENT_ID") ?: ""}\"")
    }

    signingConfigs {
        create("sideload") {
            val ksPath = secret("KEYSTORE_FILE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            } else {
                // Shared sideload key committed to the repo so every CI build can update the previous install.
                storeFile = rootProject.file("keystore/gitpush-sideload.jks")
                storePassword = "android"
                keyAlias = "gitpush"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
