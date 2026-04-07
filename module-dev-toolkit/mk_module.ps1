<#
.SYNOPSIS
    Module development entry point. Wraps pack, signing init, and keystore info.

.DESCRIPTION
    Expected directory layout:
      PandaGenie/
      ├── PandaGenieSource/           (module source, this toolkit, tools/, modules/)
      │   ├── module-dev-toolkit/     (this script lives here)
      │   └── tools/pack_modules.ps1
      └── Keystore/                   (signing keys, outside repos)

.PARAMETER Action
    pack            Build .mod files from PandaGenieSource
    init-signing    Initialize official module signing keystore
    init-dev-signing Initialize developer signing keystore
    list-keystore   Show details of all signing keystores

.PARAMETER Modules
    Comma-separated module IDs to pack (default: all).

.PARAMETER OutputDir
    Override output directory for .mod files.

.PARAMETER SkipSigning
    Pack without signing (for development testing).

.PARAMETER Alias
    Keystore alias for init-signing / init-dev-signing.

.PARAMETER DeveloperName
    Developer name for init-dev-signing.

.PARAMETER Force
    Force regeneration of signing keys.
#>
param(
    [ValidateSet("pack", "init-signing", "init-dev-signing", "list-keystore")]
    [string]$Action = "pack",
    [string]$Modules = "",
    [string]$OutputDir = "",
    [switch]$SkipSigning,
    [string]$Alias = "",
    [string]$DeveloperName = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$toolkitDir = $PSScriptRoot
$sourceRepo = Split-Path $toolkitDir -Parent

switch ($Action) {
    "pack" {
        $packScript = Join-Path $sourceRepo "tools\pack_modules.ps1"
        if (-not (Test-Path $packScript)) {
            throw "Pack script not found: $packScript`nExpected PandaGenieSource repo layout: tools/pack_modules.ps1 next to module-dev-toolkit/."
        }
        $params = @{}
        if (-not [string]::IsNullOrWhiteSpace($OutputDir)) { $params.OutputDir = $OutputDir }
        if (-not [string]::IsNullOrWhiteSpace($Modules)) { $params.Modules = $Modules }
        if ($SkipSigning) { $params.SkipSigning = $true }
        & $packScript @params
        exit $LASTEXITCODE
    }
    "init-signing" {
        $initScript = Join-Path $toolkitDir "init_module_signing.ps1"
        if (-not (Test-Path $initScript)) {
            throw "Signing init script not found: $initScript"
        }
        $sigAlias = if ([string]::IsNullOrWhiteSpace($Alias)) { "official-v1" } else { $Alias }
        & $initScript -Alias $sigAlias -Force:$Force
        exit $LASTEXITCODE
    }
    "init-dev-signing" {
        $initScript = Join-Path $toolkitDir "init_dev_signing.ps1"
        if (-not (Test-Path $initScript)) {
            throw "Dev signing init script not found: $initScript"
        }
        $devAlias = if ([string]::IsNullOrWhiteSpace($Alias)) { "dev-v1" } else { $Alias }
        $params = @{ Alias = $devAlias; Force = $Force }
        if (-not [string]::IsNullOrWhiteSpace($DeveloperName)) {
            $params.DeveloperName = $DeveloperName
        }
        & $initScript @params
        exit $LASTEXITCODE
    }
    "list-keystore" {
        $listScript = Join-Path $toolkitDir "list_keystore_info.ps1"
        if (-not (Test-Path $listScript)) {
            throw "Keystore info script not found: $listScript"
        }
        & $listScript
        exit $LASTEXITCODE
    }
}
