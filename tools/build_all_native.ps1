<#
.SYNOPSIS
    Master build script: compiles all native module libraries.
    Calls each module's build_native.ps1 in sequence.

.PARAMETER NdkPath
    Path to Android NDK. Passed through to each module's build script.

.PARAMETER Abis
    Target ABIs. Defaults to arm64-v8a,armeabi-v7a.

.PARAMETER Modules
    Specific modules to build. Defaults to all native modules.
#>
param(
    [string]$NdkPath,
    [string[]]$Abis = @("arm64-v8a", "armeabi-v7a"),
    [string[]]$Modules
)

$ErrorActionPreference = "Stop"
$ToolsDir = $PSScriptRoot
$SourceDir = Join-Path (Split-Path $ToolsDir) "source"

$nativeModules = @("calculator", "archive", "filemanager")

if ($Modules -and $Modules.Count -gt 0) {
    $nativeModules = $Modules
}

$failed = @()
$succeeded = @()

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  PandaGenie - Build All Native Libraries" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "Modules: $($nativeModules -join ', ')"
Write-Host "ABIs:    $($Abis -join ', ')"
Write-Host ""

foreach ($mod in $nativeModules) {
    $script = Join-Path $SourceDir "$mod\build_native.ps1"
    if (-not (Test-Path $script)) {
        Write-Host "SKIP: $mod (no build_native.ps1)" -ForegroundColor DarkYellow
        continue
    }

    Write-Host ""
    Write-Host ">>>>>>>>>> Building $mod <<<<<<<<<<" -ForegroundColor Magenta
    try {
        $params = @{ Abis = $Abis }
        if ($NdkPath) { $params["NdkPath"] = $NdkPath }
        & $script @params
        $succeeded += $mod
    } catch {
        Write-Host "FAILED: $mod - $_" -ForegroundColor Red
        $failed += $mod
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Build Summary" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "Succeeded: $($succeeded -join ', ')" -ForegroundColor Green
if ($failed.Count -gt 0) {
    Write-Host "Failed:    $($failed -join ', ')" -ForegroundColor Red
    exit 1
} else {
    Write-Host "All native modules built successfully!" -ForegroundColor Green
}
