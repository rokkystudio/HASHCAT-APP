plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "fuck.system.hashcat"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "fuck.system.hashcat"
        minSdk = 26
        targetSdk = 37
        versionCode = 10120
        versionName = "1.1.20"

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "armeabi-v7a",
                "x86",
                "x86_64"
            )
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libhashcat_exec.so",
                "**/libc++_shared.so",
                "**/liblzma.so"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":neoui"))
    implementation(libs.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(project(":hashcat"))
}
