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
all four published Android archives into generated Android sources:

```text
hashcat/build/generated/hashcatRuntime/jniLibs/<abi>/libhashcat_exec.so
hashcat/build/generated/hashcatRuntime/assets/hashcat/<abi>/
```

The generated tree contains `arm64-v8a`, `armeabi-v7a`, `x86` and
`x86_64`. Runtime assets are kept ABI-specific because Hashcat's plugin
modules are native binaries and must match the executable selected by Android.

Each ABI also packages `liblzma.so` from XZ Utils 5.8.4 under
`hashcat/binaries/android-libs/<abi>/`. Hashcat loads liblzma at runtime to
read its compressed `hashcat.hcstat2` Markov statistics file. Gradle treats
these four libraries as required published runtime dependencies.

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

At runtime `HashcatRunner` executes `libhashcat_exec.so` directly from
Android's extracted native-library directory. The ABI-matched Hashcat shared
runtime is copied from APK assets into `files/hashcat/runtime` and supplied to
the native process through `HASHCAT_SHARED_FOLDER`.

`LD_LIBRARY_PATH` contains the writable runtime, the app's extracted native
library directory and the process-width vendor library directory
(`/vendor/lib64` or `/vendor/lib`). The vendor directory is required on devices
whose public GPU OpenCL implementation is provided as vendor `libOpenCL.so`.

Hashcat's combined stdout/stderr stream is delivered to the in-app listener. The
Log screen appends each visible line and mirrors that same line to Android Logcat
with tag `Hashcat`. `HashcatRunner` also logs process start, exit and launch or
stream failures with the same tag. The runner enables `HASHCAT_MEMORY=1` so
Hashcat includes its memory-budget breakdown when backend allocation checks fail.

## Android execution constraint

Android 10+ does not allow an app targeting API 29+ to execute a binary copied
into its writable app-data directory. For that reason the executable remains in
Android's install-time native-library directory; only Hashcat's shared runtime
files are copied into writable app storage.

The four ABI archives are published with the repository and the Android app
packages all four ABIs. At runtime `HashcatRunner` selects the asset subtree
matching the first supported ABI for which a bundled Hashcat runtime exists.
