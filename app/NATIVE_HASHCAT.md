# Bundled native Hashcat backend

HASHCAT launches Hashcat as a native executable stored in the APK.

The prebuilt Android Hashcat distributions are part of the `:hashcat` module
and are versioned in Git under:

```text
hashcat/binaries/hashcat-v7.1.2-android-arm64-v8a.zip
hashcat/binaries/hashcat-v7.1.2-android-armeabi-v7a.zip
hashcat/binaries/hashcat-v7.1.2-android-x86.zip
hashcat/binaries/hashcat-v7.1.2-android-x86_64.zip
```

A normal Gradle build does not require a separate HASHCAT source checkout or an
import step. The `:hashcat` module's `prepareHashcatRuntime` task extracts the
currently packaged `arm64-v8a` archive into generated Android sources:

```text
hashcat/build/generated/hashcatRuntime/jniLibs/arm64-v8a/libhashcat_exec.so
hashcat/build/generated/hashcatRuntime/assets/hashcat/
```

The executable is renamed from `hashcat` to `libhashcat_exec.so` so Android
packages it as a native library. The shared runtime directories from the same
archive are packaged as assets. `jniLibs.useLegacyPackaging = true` keeps the
native executable available as an extracted file after installation.

The app depends on the runtime module with:

```kotlin
implementation(project(":hashcat"))
```

At runtime `HashcatRunner` copies the APK-extracted executable and Hashcat
assets into app-private storage and starts the portable runtime from there.

The four ABI archives are published with the repository. The Android app
currently targets `arm64-v8a`; enabling the other ABIs requires packaging each
ABI's matching plugin/runtime tree instead of sharing the arm64 asset tree.
