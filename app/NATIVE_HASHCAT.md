# Bundled native Hashcat backend

HASHCAT launches Hashcat as a native executable stored in the APK.

The prebuilt Android Hashcat distributions are part of the `:hashcat` module.
They are built from the official upstream Hashcat `v7.1.2` tag at commit
`c75f446c44cd3f0742035a1394416c39bee5ea8f`. The Android compatibility patch
does not modify hash modules or OpenCL algorithms. The same `v7.1.2` tag is
used by the native build and archive names.

The archives are versioned in Git under:

```text
hashcat/binaries/hashcat-v7.1.2-android-arm64-v8a.zip
hashcat/binaries/hashcat-v7.1.2-android-armeabi-v7a.zip
hashcat/binaries/hashcat-v7.1.2-android-x86.zip
hashcat/binaries/hashcat-v7.1.2-android-x86_64.zip
```

The separate HASHCAT source project is the build workspace that pins the
official upstream revision, applies Android portability changes during the
build and produces these archives. Once the Android archives have been copied
into `hashcat/binaries` and committed, a normal HASHCAT-APP Gradle build does
not require running that external build again.

The `:hashcat` module's `prepareHashcatRuntime` task automatically extracts
all four published Android archives into generated Android sources:

```text
hashcat/build/generated/hashcatRuntime/jniLibs/<abi>/libhashcat_exec.so
hashcat/build/generated/hashcatRuntime/jniLibs/<abi>/libc++_shared.so
hashcat/build/generated/hashcatRuntime/jniLibs/<abi>/liblzma.so
hashcat/build/generated/hashcatRuntime/assets/hashcat/<abi>/
```

The generated tree contains `arm64-v8a`, `armeabi-v7a`, `x86` and
`x86_64`. Runtime assets are kept ABI-specific because Hashcat's plugin
modules are native binaries and must match the executable selected by Android.

Each Android archive contains the ABI-matched `libc++_shared.so` required by
the native Hashcat modules. Each ABI also packages `liblzma.so` from XZ Utils
5.8.4 under `hashcat/binaries/android-libs/<abi>/`; Hashcat loads liblzma at
runtime to read its compressed `hashcat.hcstat2` Markov statistics file.
Gradle places both runtime libraries next to the native executable.

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
from `hashcat -HH` of the same pinned upstream build. The catalog currently
contains 582 modes and is regenerated with:

```powershell
.\scripts\GENERATE-HASHCAT-MODES.ps1 -HashcatExecutable '<path-to-hashcat.exe>'
```

Gradle configuration fails immediately if any of the four published Android
archives is missing. A HASHCAT-APP checkout without its published native
payload is therefore not a valid build.

At runtime `HashcatForegroundService` owns the active audit and enters Android
foreground-service state before delegating native process launch to
`HashcatRunner`. The service uses the `specialUse` foreground-service type,
keeps an ongoing notification with a Stop action and remains active if the
activity leaves the foreground. `HashcatRunner` executes `libhashcat_exec.so`
directly from Android's extracted native-library directory. The ABI-matched
Hashcat shared runtime is copied from APK assets into `files/hashcat/runtime`
and supplied to the native process through `HASHCAT_SHARED_FOLDER`.

The UI tracks input preparation, native startup, running and stopping as
separate states. The first native backend initialization can take noticeably
longer than later starts, so the status card and notification explicitly show
the initialization phase. Stop is idempotent: after the first request the
Start/Stop button remains disabled until the active native process has actually
terminated, preventing a second launch from racing the old process.

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
