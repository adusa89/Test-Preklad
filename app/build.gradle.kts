plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.testpreklad"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.testpreklad"
        minSdk = 26          // Android 8 a novější (prakticky všechny Android TV a boxy)
        targetSdk = 34
        versionCode = 8
        versionName = "0.8"
        // televize a boxy mají procesory ARM – bez x86 je APK poloviční
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // pro jednoduchost podepsáno debug klíčem, aby šlo APK rovnou nainstalovat
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
