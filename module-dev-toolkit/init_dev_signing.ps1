# Initialize developer signing keys for module identity
# This key is used as the first layer of signing to identify the module developer.
# Keystores are stored in PandaGenie/Keystore/dev_signing/ (outside any repo).

param(
    [string]$Alias = "dev-v1",
    [string]$DeveloperName = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Security

$toolkitDir = $PSScriptRoot
$modulesRepo = Split-Path $toolkitDir -Parent
$rootDir = Split-Path $modulesRepo -Parent

$signingRoot = Join-Path $rootDir "Keystore\dev_signing"
$privateDir = Join-Path $signingRoot "private"
$keystorePath = Join-Path $privateDir "dev-keystore.p12"
$secretPath = Join-Path $privateDir "signing-secret.dpapi"
$metadataPath = Join-Path $signingRoot "signing-metadata.json"
$publicCertPath = Join-Path $signingRoot "dev_cert.pem"

$logsDir = Join-Path $toolkitDir "logs"

# Find keytool (Java 11+)
$keytool = $null
$jdkCandidates = @(
    "$env:JAVA_HOME",
    "C:\Program Files\Java\jdk-17.0.18+8",
    "C:\Program Files\Java\jdk-17",
    "C:\Program Files\Java\jdk-11",
    "C:\Program Files\Android\Android Studio\jbr"
)
foreach ($jdk in $jdkCandidates) {
    if ($jdk -and (Test-Path "$jdk\bin\keytool.exe")) {
        $keytool = "$jdk\bin\keytool.exe"
        break
    }
}
if (-not $keytool) {
    $found = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "^jdk-(11|17)" } | Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { $keytool = Join-Path $found.FullName "bin\keytool.exe" }
}
if (-not $keytool -or -not (Test-Path $keytool)) {
    throw "keytool.exe not found. Install JDK 11+ or set JAVA_HOME."
}

foreach ($dir in @($logsDir, $privateDir)) {
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
}

$logPath = Join-Path $logsDir ("init_dev_signing_" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".log")
try { Start-Transcript -Path $logPath -Force | Out-Null } catch {}

if (((Test-Path $keystorePath) -or (Test-Path $metadataPath)) -and -not $Force) {
    throw "Developer signing materials already exist. Use -Force to regenerate."
}

if ($Force) {
    Remove-Item $keystorePath -Force -ErrorAction SilentlyContinue
    Remove-Item $secretPath -Force -ErrorAction SilentlyContinue
    Remove-Item $metadataPath -Force -ErrorAction SilentlyContinue
    Remove-Item $publicCertPath -Force -ErrorAction SilentlyContinue
}

if ([string]::IsNullOrWhiteSpace($DeveloperName)) {
    $DeveloperName = $env:USERNAME
    Write-Host "No developer name specified, using system username: $DeveloperName"
}

function New-RandomPassword() {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([Convert]::ToBase64String($bytes)).Replace("+", "A").Replace("/", "B")
}

function Protect-Secret($text, $path) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    $protected = [System.Security.Cryptography.ProtectedData]::Protect(
        $bytes,
        $null,
        [System.Security.Cryptography.DataProtectionScope]::CurrentUser
    )
    [System.IO.File]::WriteAllText($path, [Convert]::ToBase64String($protected))
}

function Get-CertFingerprint($pemPath) {
    $pem = Get-Content $pemPath -Raw
    $base64Body = ($pem -split "`r?`n" | Where-Object { $_ -notmatch "^-----" }) -join ""
    $certBytes = [Convert]::FromBase64String($base64Body)
    $hashBytes = [System.Security.Cryptography.SHA256]::Create().ComputeHash($certBytes)
    return ([System.BitConverter]::ToString($hashBytes)).Replace("-", "")
}

$signingPassword = New-RandomPassword

& $keytool `
    -genkeypair `
    -alias $Alias `
    -keyalg RSA `
    -keysize 3072 `
    -sigalg SHA256withRSA `
    -validity 3650 `
    -storetype PKCS12 `
    -keystore $keystorePath `
    -storepass $signingPassword `
    -keypass $signingPassword `
    -dname "CN=$DeveloperName, OU=Developer, O=PandaGenie Module Developer, C=CN"

if ($LASTEXITCODE -ne 0) {
    throw "keytool key generation failed"
}

& $keytool `
    -exportcert `
    -rfc `
    -alias $Alias `
    -keystore $keystorePath `
    -storetype PKCS12 `
    -storepass $signingPassword `
    -file $publicCertPath

if ($LASTEXITCODE -ne 0) {
    throw "keytool certificate export failed"
}

Protect-Secret $signingPassword $secretPath

$fingerprint = Get-CertFingerprint $publicCertPath

$metadata = @{
    alias = $Alias
    keystore = $keystorePath
    publicCert = $publicCertPath
    fingerprintSha256 = $fingerprint
    developerName = $DeveloperName
    createdAt = (Get-Date).ToString("s")
    protection = "Keystore password is protected with Windows DPAPI (CurrentUser)"
    purpose = "Developer identity signing - first layer, used for module ID conflict detection"
}

$metadata | ConvertTo-Json -Depth 5 | Set-Content $metadataPath -Encoding UTF8

Write-Host "=========================================="
Write-Host "  Developer signing initialized"
Write-Host "=========================================="
Write-Host "Developer:  $DeveloperName"
Write-Host "Keystore:   $keystorePath"
Write-Host "Public cert:$publicCertPath"
Write-Host "Fingerprint:$fingerprint"
Write-Host "Log:        $logPath"
Write-Host ""

try { Stop-Transcript | Out-Null } catch {}
