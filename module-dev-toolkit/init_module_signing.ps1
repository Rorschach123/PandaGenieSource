# Initialize official module signing keys
# Creates PKCS12 keystore + exports public cert for app-side verification.
# Keystores are stored in PandaGenie/Keystore/module_signing/ (outside any repo).

param(
    [string]$Alias = "official-v1",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Security

$toolkitDir = $PSScriptRoot
$modulesRepo = Split-Path $toolkitDir -Parent
$rootDir = Split-Path $modulesRepo -Parent

$signingRoot = Join-Path $rootDir "Keystore\module_signing"
$privateDir = Join-Path $signingRoot "private"
$keystorePath = Join-Path $privateDir "official-keystore.p12"
$secretPath = Join-Path $privateDir "signing-secret.dpapi"
$metadataPath = Join-Path $signingRoot "signing-metadata.json"
$publicCertPath = Join-Path $signingRoot "official_cert.pem"

$logsDir = Join-Path $toolkitDir "logs"

# Optional: copy cert into app assets if AndroidProject exists
$appAssetsDir = Join-Path $rootDir "AndroidProject\app\src\main\assets\module_signing"
# Also export a copy into the source repo's keys/ dir (for reference)
$sourceRepo = Join-Path $rootDir "PandaGenieSource"
$sourceKeysDir = Join-Path $sourceRepo "keys"

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

$logPath = Join-Path $logsDir ("init_module_signing_" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".log")
try { Start-Transcript -Path $logPath -Force | Out-Null } catch {}

if (((Test-Path $keystorePath) -or (Test-Path $metadataPath)) -and -not $Force) {
    throw "Signing materials already exist. Use -Force only when you really want to replace the official key."
}

if ($Force) {
    Remove-Item $keystorePath -Force -ErrorAction SilentlyContinue
    Remove-Item $secretPath -Force -ErrorAction SilentlyContinue
    Remove-Item $metadataPath -Force -ErrorAction SilentlyContinue
    Remove-Item $publicCertPath -Force -ErrorAction SilentlyContinue
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
    -dname "CN=PandaGenie Official Module Signing, OU=Modules, O=PandaGenie, C=CN"

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

# Copy public cert to app assets if the project exists
if (Test-Path (Split-Path $appAssetsDir)) {
    if (-not (Test-Path $appAssetsDir)) { New-Item -ItemType Directory -Path $appAssetsDir -Force | Out-Null }
    Copy-Item $publicCertPath (Join-Path $appAssetsDir "official_cert.pem") -Force
    Write-Host "  Cert copied to app assets: $appAssetsDir"
}

# Copy to source repo keys/ for reference
if (Test-Path $sourceRepo) {
    if (-not (Test-Path $sourceKeysDir)) { New-Item -ItemType Directory -Path $sourceKeysDir -Force | Out-Null }
    Copy-Item $publicCertPath (Join-Path $sourceKeysDir "official_cert.pem") -Force
    Write-Host "  Cert copied to source repo: $sourceKeysDir"
}

Protect-Secret $signingPassword $secretPath

$fingerprint = Get-CertFingerprint $publicCertPath

$metadata = @{
    alias = $Alias
    keystore = $keystorePath
    publicCert = $publicCertPath
    fingerprintSha256 = $fingerprint
    createdAt = (Get-Date).ToString("s")
    protection = "Keystore password is protected with Windows DPAPI (CurrentUser)"
    backupDesign = @(
        "1. Keep the keystore outside any shared or synced folder.",
        "2. Export an offline encrypted backup before moving to another machine.",
        "3. Store the recovery backup and its passphrase separately.",
        "4. If the private key is suspected stolen, rotate to a new alias and rebuild the app with the new public certificate."
    )
}

$metadata | ConvertTo-Json -Depth 5 | Set-Content $metadataPath -Encoding UTF8

Write-Host "=========================================="
Write-Host "  Official module signing initialized"
Write-Host "=========================================="
Write-Host "Keystore:   $keystorePath"
Write-Host "Public cert:$publicCertPath"
Write-Host "Fingerprint:$fingerprint"
Write-Host "Log:        $logPath"
Write-Host ""
Write-Host "Private keystore and password are stored separately."
Write-Host "For anti-theft / loss protection:"
Write-Host "  - password is DPAPI-protected for the current Windows user"
Write-Host "  - keep Keystore/module_signing/private off shared disks"
Write-Host "  - make an offline backup of the keystore before changing machines"

try { Stop-Transcript | Out-Null } catch {}
