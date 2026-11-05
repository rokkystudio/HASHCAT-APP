<#
.SYNOPSIS
    Builds libhc_arena.so for every packaged ABI.

.DESCRIPTION
    libhc_arena.so is the LD_PRELOAD heap shim that keeps the ARM Mali OpenCL
    compiler below vm.max_map_count for large kernels (hashcat mode 22000 and
    friends). See scripts/native/hc-arena/arena.c for the full explanation.

    The shared objects are prebuilt on purpose: the app module has no CMake
    surface of its own, and the runtime native libraries come from the
    published hashcat archive, so a Gradle-only build cannot produce this file.

    Requires the Android NDK; pass -AndroidNdk or set ANDROID_NDK_HOME.
#>

param(
    [string] $AndroidNdk = $env:ANDROID_NDK_HOME,
    [string] $Msys2Bash  = 'C:\msys64\usr\bin\bash.exe'
)

$ErrorActionPreference = 'Stop'

if (-not $AndroidNdk) { throw 'Android NDK not specified. Pass -AndroidNdk or set ANDROID_NDK_HOME.' }

$repoRoot = Split-Path -Parent $PSScriptRoot
$source   = Join-Path $repoRoot 'scripts\native\hc-arena\arena.c'
$jniRoot  = Join-Path $repoRoot 'app\src\main\jniLibs'
$clangRoot = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\bin'

if (-not (Test-Path $source)) { throw "Missing source: $source" }
if (-not (Test-Path $clangRoot)) { throw "Missing NDK toolchain: $clangRoot" }

# minSdk 26 -> target API 26 for every ABI.
$targets = [ordered]@{
    'arm64-v8a'   = 'aarch64-linux-android26-clang.cmd'
    'armeabi-v7a' = 'armv7a-linux-androideabi26-clang.cmd'
    'x86'         = 'i686-linux-android26-clang.cmd'
    'x86_64'      = 'x86_64-linux-android26-clang.cmd'
}

$common = @(
    '-O2',
    '-fPIC',
    '-shared',
    '-fno-builtin',
    '-Wall',
    '-Wl,-soname,libhc_arena.so',
    '-Wl,--build-id=sha1'
)

foreach ($abi in $targets.Keys)
{
    $compiler = Join-Path $clangRoot $targets[$abi]
    $outDir   = Join-Path $jniRoot $abi
    $outFile  = Join-Path $outDir 'libhc_arena.so'

    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    & $compiler @common -o $outFile $source

    if ($LASTEXITCODE -ne 0) { throw "Compilation failed for $abi" }
    if (-not (Test-Path $outFile)) { throw "Missing output for $abi" }

    $size = (Get-Item $outFile).Length
    Write-Host ("built {0} ({1} bytes)" -f $outFile, $size)
}

Write-Host 'HC_ARENA_BUILD_DONE'
