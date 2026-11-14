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
        versionCode = 3
        versionName = "0.1.2"
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

val rustCrateDir = rootProject.file("native/gigaam_core")
val jniLibsDir = file("src/main/jniLibs")
// An IDE-launched Gradle may not see the shell PATH, so prefer rustup's install location.
val cargoExecutable = (System.getenv("CARGO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cargo"))
    .resolve("bin")
    .let { bin -> listOf("cargo.exe", "cargo").map(bin::resolve).firstOrNull { it.isFile }?.path }
    ?: "cargo"

val buildRustCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Build Rust native core for Android"
    onlyIf { !skipRustBuild.get() }

    // cargo-ndk 4 reads Cargo.toml from the working directory and ignores --manifest-path.
    workingDir = rustCrateDir
    commandLine(
        listOf(cargoExecutable, "ndk", "-t", rustAbi.get(), "-o", jniLibsDir.absolutePath, "build") +
            if (rustProfile.get() == "release") listOf("--release") else emptyList(),
    )
    doFirst {
        // Without ANDROID_NDK_HOME, use the NDK that AGP resolves from ndkVersion and sdk.dir.
        if (System.getenv("ANDROID_NDK_HOME").isNullOrBlank()) {
            environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
        }
    }

    inputs.files(
        fileTree(rustCrateDir.resolve("src")) { include("**/*.rs") },
        rustCrateDir.resolve("Cargo.toml"),
        rustCrateDir.resolve("Cargo.lock"),
    )
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild").configure {
    dependsOn(buildRustCore)
}
