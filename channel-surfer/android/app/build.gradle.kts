plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.channelsurfer.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.channelsurfer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val rustOut = layout.projectDirectory.dir("src/main/jniLibs")
val buildRust by tasks.registering(Exec::class) {
    workingDir = rootProject.file("rust")
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-o", rustOut.asFile.absolutePath, "build", "--release")
    inputs.files(fileTree(rootProject.file("rust/src")), rootProject.file("rust/Cargo.toml"))
    outputs.dir(rustOut)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildRust)
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
}
