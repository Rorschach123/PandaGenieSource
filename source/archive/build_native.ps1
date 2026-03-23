<#
.SYNOPSIS
    Standalone NDK build script for the archive module.
    Compiles libarchive_module.so for arm64-v8a and armeabi-v7a.
#>
param(
    [string]$NdkPath,
    [string[]]$Abis = @("arm64-v8a", "armeabi-v7a"),
    [int]$ApiLevel = 24
)

$ErrorActionPreference = "Stop"
$ModuleDir = $PSScriptRoot
$NativeDir = Join-Path $ModuleDir "native"
$BuildRoot = Join-Path $NativeDir "build"

function Find-Ndk {
    if ($NdkPath -and (Test-Path $NdkPath)) { return $NdkPath }
    if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) { return $env:ANDROID_NDK_HOME }

    $sdkDir = $null
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { $sdkDir = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { $sdkDir = $env:ANDROID_SDK_ROOT }
    else {
        $localProps = Join-Path $ModuleDir "..\..\..\..\AndroidProject\local.properties"
        if (Test-Path $localProps) {
            $content = Get-Content $localProps -Raw
            if ($content -match 'sdk\.dir\s*=\s*(.+)') {
                $sdkDir = $Matches[1].Trim().Replace("\\:", ":").Replace("\\\\", "\")
            }
        }
    }
    if (-not $sdkDir) {
        $sdkDir = "$env:LOCALAPPDATA\Android\Sdk"
    }

    $ndkDir = Join-Path $sdkDir "ndk"
    if (Test-Path $ndkDir) {
        $latest = Get-ChildItem $ndkDir -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($latest) { return $latest.FullName }
    }
    throw "Cannot find Android NDK. Set ANDROID_NDK_HOME or pass -NdkPath."
}

$ndk = Find-Ndk
$toolchainFile = Join-Path $ndk "build\cmake\android.toolchain.cmake"
if (-not (Test-Path $toolchainFile)) {
    throw "NDK toolchain file not found at: $toolchainFile"
}

$sdkDir = Split-Path (Split-Path $ndk -Parent) -Parent
$cmakeDir = Join-Path $sdkDir "cmake"
$cmake = $null
if (Test-Path $cmakeDir) {
    $cmake = Get-ChildItem $cmakeDir -Recurse -Filter "cmake.exe" -ErrorAction SilentlyContinue |
             Where-Object { $_.Directory.Name -eq "bin" } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $cmake) {
    $cmake = Get-Command cmake -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
}
if (-not $cmake) { throw "cmake not found. Install CMake via Android SDK Manager or add to PATH." }

$ninja = Join-Path (Split-Path $cmake -Parent) "ninja.exe"
if (-not (Test-Path $ninja)) { $ninja = "ninja" }

Write-Host "=== Building archive native library ===" -ForegroundColor Cyan
Write-Host "NDK: $ndk"
Write-Host "CMake: $cmake"
Write-Host "ABIs: $($Abis -join ', ')"
Write-Host ""

foreach ($abi in $Abis) {
    Write-Host "--- Building for $abi ---" -ForegroundColor Yellow
    $buildDir = Join-Path $BuildRoot $abi
    if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
    New-Item -ItemType Directory -Path $buildDir -Force | Out-Null

    $outputDir = Join-Path $BuildRoot "libs\$abi"
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

    & $cmake `
        -G Ninja `
        -S $NativeDir `
        -B $buildDir `
        -DCMAKE_TOOLCHAIN_FILE="$toolchainFile" `
        -DCMAKE_MAKE_PROGRAM="$ninja" `
        -DANDROID_ABI="$abi" `
        -DANDROID_PLATFORM="android-$ApiLevel" `
        -DCMAKE_ANDROID_NDK="$ndk" `
        -DCMAKE_BUILD_TYPE=Release `
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$outputDir"

    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed for $abi" }

    & $cmake --build $buildDir --config Release -j $env:NUMBER_OF_PROCESSORS

    if ($LASTEXITCODE -ne 0) { throw "CMake build failed for $abi" }

    $soFile = Get-ChildItem $outputDir -Filter "*.so" | Select-Object -First 1
    if ($soFile) {
        Write-Host "  Output: $($soFile.FullName) ($([math]::Round($soFile.Length / 1KB, 1)) KB)" -ForegroundColor Green
    } else {
        throw "No .so file produced for $abi"
    }
}

Write-Host ""
Write-Host "=== archive native build complete ===" -ForegroundColor Green
Write-Host "Output directory: $(Join-Path $BuildRoot 'libs')"
