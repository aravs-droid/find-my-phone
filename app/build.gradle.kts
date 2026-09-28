plugins {
    id("com.android.application")
}

android {
    namespace = "net.media.wakeword"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }  // android-36.1 is what's installed locally
    }

    defaultConfig {
        applicationId = "net.media.wakeword"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-hey_find_my_phone"
        ndk {
            // Phones + x86_64 emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Test build: signed with the local debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // LiteRT (formerly TensorFlow Lite): provides org.tensorflow.lite.Interpreter.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
}
