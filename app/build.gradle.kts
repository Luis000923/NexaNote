import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nexanote.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nexanote.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ABIs para las que el núcleo Rust se compila (ver rust/build-android.sh).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
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
    }
    // Los .so del núcleo Rust se colocan en app/src/main/jniLibs/<abi>/ por el script.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

/**
 * Compila el núcleo Rust (rust/nexanote-core) para las ABIs de Android y copia
 * los .so a jniLibs antes de empaquetar. Requiere `cargo` y el NDK en el PATH /
 * ANDROID_NDK_HOME. Si no están disponibles, el build falla con un mensaje claro.
 */
val buildRustCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Compila el núcleo Rust para Android y lo copia a jniLibs."
    workingDir = rootProject.file("rust")
    val script = if (OperatingSystem.current().isWindows) "build-android.ps1" else "build-android.sh"
    commandLine(if (OperatingSystem.current().isWindows) listOf("powershell", "-File", script) else listOf("bash", script))
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildRustCore)
}
