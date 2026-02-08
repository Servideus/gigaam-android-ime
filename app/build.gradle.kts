plugins {
    id("com.android.application")
    kotlin("android")
}

val skipRustBuild = providers.gradleProperty("skipRustBuild")
    .map { it.toBooleanStrictOrNull() ?: false }
    .orElse(false)
val rustAbi = providers.gradleProperty("rustAbi").orElse("arm64-v8a")
val rustProfile = providers.gradleProperty("rustProfile").orElse("release")

android {
    namespace = "com.servideus.gigaamime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.servideus.gigaamime"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }

    ndkVersion = "27.2.12479018"
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

val buildRustCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Build Rust native core for Android"
    workingDir = rootProject.projectDir

    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val resolvedAbi = rustAbi.get()
    val resolvedProfile = rustProfile.get()
    val profileFlag = if (resolvedProfile == "release") "--release" else ""

    if (isWindows) {
        commandLine(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            rootProject.file("scripts/build-rust-android.ps1").absolutePath,
            "-Abi",
            resolvedAbi,
            "-Profile",
            resolvedProfile,
        )
    } else {
        commandLine(
            "bash",
            "-lc",
            "cargo ndk -t $resolvedAbi -o app/src/main/jniLibs build --manifest-path native/gigaam_core/Cargo.toml $profileFlag",
        )
    }

    onlyIf { !skipRustBuild.get() }
    inputs.files(
        fileTree(rootProject.file("native/gigaam_core/src")) {
            include("**/*.rs")
        },
        rootProject.file("native/gigaam_core/Cargo.toml"),
    )
    outputs.dir(file("src/main/jniLibs"))
}

tasks.named("preBuild").configure {
    dependsOn(buildRustCore)
}
