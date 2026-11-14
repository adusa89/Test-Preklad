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
        versionCode = 11
        versionName = "0.11"

        // televize a boxy mají procesory ARM; emulátor při zkoušce x86 (-Pabis=x86)
        val abis = ((project.findProperty("abis") as String?) ?: "armeabi-v7a,arm64-v8a").split(",")
        ndk { abiFilters += abis }

        // spojení s PlayStationem: otevřená knihovna chiaki-ng (third_party/chiaki-ng)
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCHIAKI_ENABLE_TESTS=OFF", "-DCHIAKI_ENABLE_CLI=OFF", "-DCHIAKI_ENABLE_GUI=OFF",
                    "-DCHIAKI_ENABLE_SETSU=OFF", "-DCHIAKI_ENABLE_ANDROID=ON",
                    "-DCHIAKI_ENABLE_FFMPEG_DECODER=OFF", "-DCHIAKI_ENABLE_PI_DECODER=OFF",
                    "-DCHIAKI_LIB_ENABLE_OPUS=OFF", "-DCHIAKI_LIB_OPENSSL_EXTERNAL_PROJECT=ON",
                    "-DCHIAKI_LIB_JSONC_EXTERNAL_PROJECT=ON", "-DCHIAKI_LIB_MINIUPNPC_EXTERNAL_PROJECT=ON",
                    "-DCHIAKI_ENABLE_STEAM_SHORTCUT=OFF", "-DCHIAKI_ENABLE_STEAMDECK_NATIVE=OFF",
                    "-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384",
                )
            }
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("../third_party/chiaki-ng/CMakeLists.txt")
        }
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

// Naše úprava nativní části (bez zvuku a bez nativního dekodéru videa) se před sestavením
// zkopíruje na místo původní android/app v knihovně chiaki-ng.
val patchChiaki by tasks.registering(Copy::class) {
    from("src/main/cpp")
    into("../third_party/chiaki-ng/android/app")
}
tasks.named("preBuild") { dependsOn(patchChiaki) }

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
