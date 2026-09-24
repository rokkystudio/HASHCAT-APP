plugins {
    alias(libs.plugins.android.library)
}

val hashcatVersion = "7.1.2"
val androidAbi = "arm64-v8a"
val runtimeArchive = layout.projectDirectory.file(
    "binaries/hashcat-v$hashcatVersion-android-$androidAbi.zip"
)
val generatedRuntimeDir = layout.buildDirectory.dir("generated/hashcatRuntime")

val prepareHashcatRuntime by tasks.registering(Copy::class) {
    group = "build setup"
    description = "Extracts the published Hashcat Android runtime into generated Android sources."

    from(zipTree(runtimeArchive.asFile)) {
        include(
            "modules/**",
            "bridges/**",
            "feeds/**",
            "OpenCL/**",
            "rules/**",
            "tunings/**",
            "pcfg/**",
            "hashcat.hcstat2"
        )
        into("assets/hashcat")
    }

    from(zipTree(runtimeArchive.asFile)) {
        include("hashcat")
        into("jniLibs/$androidAbi")
        rename("hashcat", "libhashcat_exec.so")
    }

    into(generatedRuntimeDir)
}

android {
    namespace = "fuck.system.hashcat.runtime"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        // The app currently ships the arm64-v8a runtime. All published Android
        // Hashcat archives remain versioned under hashcat/binaries.
        ndk {
            abiFilters += listOf(androidAbi)
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("android/src/main/AndroidManifest.xml")
            assets.srcDir("build/generated/hashcatRuntime/assets")
            jniLibs.srcDir("build/generated/hashcatRuntime/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libhashcat_exec.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.named("preBuild") {
    dependsOn(prepareHashcatRuntime)
}
