# List detailed info for each signing keystore under PandaGenie/Keystore/.
# Decrypts DPAPI-protected passwords and calls keytool -list -v.

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Security

$toolkitDir = $PSScriptRoot
$modulesRepo = Split-Path $toolkitDir -Parent
$rootDir = Split-Path $modulesRepo -Parent
$keystoreRoot = Join-Path $rootDir "Keystore"

# Find keytool (Java 11+)
$jdkPath = $null
$jdkCandidates = @(
    "$env:JAVA_HOME",
    "C:\Program Files\Java\jdk-17.0.18+8",
    "C:\Program Files\Java\jdk-17",
    "C:\Program Files\Java\jdk-11",
    "C:\Program Files\Android\Android Studio\jbr"
)
foreach ($jdk in $jdkCandidates) {
    if ($jdk -and (Test-Path "$jdk\bin\keytool.exe")) {
        try {
            $verOut = & "$jdk\bin\java" -version 2>&1 | Out-String
            if (-not ($verOut -match '"1\.8\.' -or $verOut -match 'version "8"')) {
                $jdkPath = $jdk; break
            }
        } catch {}
    }
}
if (-not $jdkPath) {
    $found = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "^jdk-(11|17)" } | Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { $jdkPath = $found.FullName }
}
$keytool = Join-Path $jdkPath "bin\keytool.exe"
if (-not (Test-Path $keytool)) {
    Write-Host "keytool not found. Install JDK 11+ or set JAVA_HOME."
    exit 1
}

function Get-KeystorePassword($secretPath) {
    if (-not (Test-Path $secretPath)) { return $null }
    $protectedBase64 = Get-Content $secretPath -Raw
    $protectedBytes = [Convert]::FromBase64String($protectedBase64.Trim())
    $bytes = [System.Security.Cryptography.ProtectedData]::Unprotect(
        $protectedBytes,
        $null,
        [System.Security.Cryptography.DataProtectionScope]::CurrentUser
    )
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

if (-not (Test-Path $keystoreRoot)) {
    Write-Host "Keystore directory not found: $keystoreRoot"
    exit 1
}

$signingDirs = @(
    @{ Name = "module_signing (Official)"; SubDir = "module_signing" },
    @{ Name = "dev_signing (Developer)"; SubDir = "dev_signing" },
    @{ Name = "apk_signing (APK Release)"; SubDir = "apk_signing" }
)

foreach ($sig in $signingDirs) {
    $base = Join-Path $keystoreRoot $sig.SubDir
    if (-not (Test-Path $base)) { continue }
    $privateDir = Join-Path $base "private"
    $secretPath = Join-Path $privateDir "signing-secret.dpapi"
    $p12Files = @(Get-ChildItem $privateDir -Filter "*.p12" -ErrorAction SilentlyContinue)
    if (-not $p12Files.Count) { continue }
    $password = Get-KeystorePassword $secretPath
    if (-not $password) {
        Write-Host "`n=== $($sig.Name) === (no signing-secret.dpapi or decrypt failed)"
    }
    foreach ($p12 in $p12Files) {
        Write-Host "`n========== $($sig.Name): $($p12.Name) =========="
        if ($password) {
            & $keytool -list -v -keystore $p12.FullName -storetype PKCS12 -storepass $password 2>&1
        } else {
            Write-Host "(run keytool manually: keytool -list -v -keystore `"$($p12.FullName)`" -storetype PKCS12)"
        }
    }
}
Write-Host "`nDone."
