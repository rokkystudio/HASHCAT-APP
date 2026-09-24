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

The separate HASHCAT source project remains the build workspace used to
download/build Hashcat for Android and other platforms and to produce these
archives. Once the Android archives have been copied into `hashcat/binaries`
and committed, a normal HASHCAT-APP Gradle build does not require running that
external build again or performing a manual import step.

The `:hashcat` module's `prepareHashcatRuntime` task automatically extracts
the currently packaged `arm64-v8a` archive into generated Android sources:

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

At application startup `HashcatRunner` prepares the portable runtime on a
worker thread. Runtime extraction details are not part of the main UI. The
hash-mode selector uses `app/src/main/assets/hashcat-modes.json`, generated
from the Hashcat v7.1.2 module metadata, so the large mode catalog can be
grouped and searched without starting Hashcat.

Gradle configuration fails immediately if any of the four published Android
archives is missing. A HASHCAT-APP checkout without its published native
payload is therefore not a valid build.

At runtime `HashcatRunner` copies the APK-extracted executable and Hashcat
assets into app-private storage before starting the portable runtime.

## Android execution constraint

Android 10+ does not allow an app targeting API 29+ to execute a binary copied
into its writable app-data directory. The current Hashcat Android archive is a
CLI executable that expects its shared runtime tree beside the executable, so
the old copy-and-exec approach cannot be the final runtime architecture for
this app. The Android build produced by the external HASHCAT project must
either expose an app-supplied shared/runtime directory while the executable
runs from Android's extracted native-library directory, or provide a native
library/JNI entry point. This is separate from background extraction and the
hash-mode UI.

The four ABI archives are published with the repository. The Android app
currently targets `arm64-v8a`; enabling the other ABIs requires packaging each
ABI's matching plugin/runtime tree instead of sharing the arm64 asset tree.
