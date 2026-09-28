plugins {
    alias(libs.plugins.android.library)
}

val hashcatVersion = "7.1.2"
val publishedAndroidAbis = listOf(
    "arm64-v8a",
    "armeabi-v7a",
    "x86",
    "x86_64"
)
val publishedArchives = publishedAndroidAbis.associateWith { abi ->
    layout.projectDirectory.file(
        "binaries/hashcat-v$hashcatVersion-android-$abi.zip"
    )
}
val publishedAndroidLibraries = publishedAndroidAbis.associateWith { abi ->
    layout.projectDirectory.file("binaries/android-libs/$abi/liblzma.so")
}
val generatedRuntimeDir = layout.buildDirectory.dir("generated/hashcatRuntime")

val missingPublishedArchives = publishedArchives
    .values
    .map { it.asFile }
    .filterNot(File::isFile)

if (missingPublishedArchives.isNotEmpty()) {
    throw GradleException(
        "HASHCAT-APP requires the published Hashcat binaries. Missing: " +
            missingPublishedArchives.joinToString { it.name }
    )
}

val missingPublishedLibraries = publishedAndroidLibraries
    .values
    .map { it.asFile }
    .filterNot(File::isFile)

if (missingPublishedLibraries.isNotEmpty()) {
    throw GradleException(
        "HASHCAT-APP requires the published Android runtime libraries. Missing: " +
            missingPublishedLibraries.joinToString { it.path }
    )
}

val prepareHashcatRuntime by tasks.registering(Sync::class) {
    group = "build setup"
    description = "Extracts every published Hashcat Android ABI into generated Android sources."

    publishedArchives.forEach { (abi, archive) ->
        from(zipTree(archive.asFile)) {
            include(
                "modules/**",
                "bridges/**",
                "OpenCL/**",
                "rules/**",
                "tunings/**",
                "hashcat.hcstat2"
            )
            into("assets/hashcat/$abi")
        }

        from(zipTree(archive.asFile)) {
            include("hashcat")
            into("jniLibs/$abi")
            rename("hashcat", "libhashcat_exec.so")
        }

        from(zipTree(archive.asFile)) {
            include("libc++_shared.so")
            into("jniLibs/$abi")
        }

        from(publishedAndroidLibraries.getValue(abi).asFile) {
            into("jniLibs/$abi")
        }
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

        ndk {
            abiFilters += publishedAndroidAbis
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
