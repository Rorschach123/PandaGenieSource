# Signed module packaging script
# Usage: .\pack_modules.ps1 [-Modules "ocr"] [-Modules "ocr,calculator"] [-OutputDir "path"] [-SkipSigning] [-DevKey "Keystore\dev_signing_jarvan"]

param(
    [string]$OutputDir = "",
    [string]$Modules = "",
    [string]$DevKey = "",
    [switch]$SkipSigning
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Security

$sourceRoot = Split-Path $PSScriptRoot -Parent
$rootDir = Split-Path $sourceRoot -Parent
$srcBase = Join-Path $sourceRoot "source"
$sharedApiDir = Join-Path $srcBase "shared_api"
$logsDir = Join-Path $sourceRoot "logs"
# Default output: modules/ directory inside PandaGenieSource
$distDir = if ([string]::IsNullOrWhiteSpace($OutputDir)) { Join-Path $sourceRoot "modules" } else { $OutputDir }
$modulesJsonPath = Join-Path $sourceRoot "modules.json"

$abis = @("arm64-v8a", "armeabi-v7a")

# OCR module: merge full Google ML Kit + Firebase + datatransport dependency tree into plugin.jar (MODULE_DEPENDENCIES.md).
# Plugin ClassLoader only loads from plugin.jar; all com.google.* used by MlKit/InputImage must be inside it.
$gradleCacheTransforms = Join-Path $env:USERPROFILE ".gradle\caches\transforms-3"
$mlkitJars = @()
if (Test-Path $gradleCacheTransforms) {
    $seen = @{}
    # Collect every *-runtime.jar under transforms-3 whose path or filename suggests Google ML Kit / Firebase / datatransport / GMS.
    # Match any -runtime.jar that is part of Google ML Kit / Firebase / GMS / datatransport / Dagger (full dependency tree).
    # transport-runtime needs dagger.internal.Factory (com.google.dagger:dagger).
    $namePathRegex = 'mlkit|firebase|gms|datatransport|play-services|transport|vision-common|vision-interfaces|text-recognition|common-[\d\.]+-runtime|basement|base-[\d\.]+-runtime|tasks-[\d\.]+-runtime|cct|protobuf|dagger|javax\.inject'
    Get-ChildItem -Path $gradleCacheTransforms -Recurse -Filter "*-runtime.jar" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($seen[$_.FullName]) { return }
        if ($_.FullName -match $namePathRegex -or $_.Name -match $namePathRegex) {
            $seen[$_.FullName] = $true
            $mlkitJars += $_.FullName
        }
    }
    # Firebase/ML Kit/datransport/Dagger artifact names
    Get-ChildItem -Path $gradleCacheTransforms -Recurse -Filter "*-runtime.jar" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($seen[$_.FullName]) { return }
        if ($_.Name -match 'firebase-(common|encoders|components|installations|annotations)|^common-\d|transport-backend-cct|transport-api|transport-runtime|^dagger-|javax\.inject') {
            $seen[$_.FullName] = $true
            $mlkitJars += $_.FullName
        }
    }
    # Dagger + javax.inject (any .jar, not only -runtime): transport-runtime.dagger.internal.Factory needs javax.inject.Provider
    Get-ChildItem -Path $gradleCacheTransforms -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($seen[$_.FullName]) { return }
        if ($_.Name -match '^dagger-|^javax\.inject|javax-inject') {
            $seen[$_.FullName] = $true
            $mlkitJars += $_.FullName
        }
    }
}
# Fallback: modules-2 cache (raw JARs). Include com.google.android.datatransport and Dagger.
if ($mlkitJars.Count -eq 0) {
    $modulesCache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"
    $mlkitGroups = @("com.google.mlkit", "com.google.android.gms", "com.google.firebase", "com.google.android.datatransport", "com.google.dagger", "javax.inject")
    $nameRegex = 'common-|vision-|text-recognition|play-services|firebase-|transport-|^dagger-|javax\.inject|javax-inject'
    $seen = @{}
    foreach ($group in $mlkitGroups) {
        $groupPath = Join-Path $modulesCache ($group -replace '\.', '\')
        if (-not (Test-Path $groupPath)) { continue }
        Get-ChildItem -Path $groupPath -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.Name.EndsWith("-sources.jar")) { return }
            if ($_.Name -match $nameRegex -and -not $seen[$_.FullName]) {
                $seen[$_.FullName] = $true
                $mlkitJars += $_.FullName
            }
        }
    }
}
# OCR: ensure javax.inject + full transport-runtime (with dagger.internal.Factory). Download from Maven if missing.
$ocrLibsDir = Join-Path $sourceRoot "source\ocr\libs"
if ($mlkitJars.Count -gt 0) {
    if (-not (Test-Path $ocrLibsDir)) { New-Item -ItemType Directory -Path $ocrLibsDir -Force | Out-Null }
    $hasJavaxInject = $false
    foreach ($j in $mlkitJars) {
        if ($j -match 'javax\.inject|javax-inject') { $hasJavaxInject = $true; break }
    }
    if (-not $hasJavaxInject) {
        $javaxInjectJar = Join-Path $ocrLibsDir "javax.inject-1.jar"
        if (-not (Test-Path $javaxInjectJar)) {
            try {
                Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar" -OutFile $javaxInjectJar -UseBasicParsing
                Write-Host "  + downloaded javax.inject for OCR plugin" -ForegroundColor DarkGray
            } catch { Write-Host "  ! download javax.inject failed: $_" -ForegroundColor Yellow }
        }
        if (Test-Path $javaxInjectJar) { $mlkitJars += $javaxInjectJar }
    }
}
# SDK paths: prefer env vars, fall back to well-known defaults
$androidSdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
    else { "$env:LOCALAPPDATA\Android\Sdk" }

$androidPlatformVer = if ($env:ANDROID_PLATFORM) { $env:ANDROID_PLATFORM } else { "android-34" }
$androidBuildTools = if ($env:ANDROID_BUILD_TOOLS) { $env:ANDROID_BUILD_TOOLS } else {
    $btDir = Join-Path $androidSdkRoot "build-tools"
    if (Test-Path $btDir) {
        $latest = Get-ChildItem $btDir -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($latest) { $latest.Name } else { "35.0.0" }
    } else { "35.0.0" }
}

$androidJar = Join-Path $androidSdkRoot "platforms\$androidPlatformVer\android.jar"
$d8 = Join-Path $androidSdkRoot "build-tools\$androidBuildTools\d8.bat"
$javac = "$env:JAVA_HOME\bin\javac.exe"
$jarExe = "$env:JAVA_HOME\bin\jar.exe"
$jarsigner = "$env:JAVA_HOME\bin\jarsigner.exe"

# d8 requires Java 11+ (class file 55+). If current JAVA_HOME is Java 8, use a fallback JDK for d8 only.
$jdkForD8 = $env:JAVA_HOME
if ($jdkForD8 -and (Test-Path "$jdkForD8\bin\java.exe")) {
    try {
        $verOut = & "$jdkForD8\bin\java" -version 2>&1 | Out-String
        if ($verOut -match '"1\.8\.' -or $verOut -match 'version "8') { $jdkForD8 = $null }
    } catch { $jdkForD8 = $null }
}
if (-not $jdkForD8 -or -not (Test-Path "$jdkForD8\bin\java.exe")) {
    $candidates = @(
        "C:\Program Files\Java\jdk-17.0.18+8",
        "C:\Program Files\Java\jdk-17",
        "C:\Program Files\Java\jdk-11",
        "$env:LOCALAPPDATA\Android\Sdk\jdk\*",
        "C:\Program Files\Android\Android Studio\jbr"
    )
    foreach ($c in $candidates) {
        $path = $c
        if ($c -like "*\*") { $path = (Resolve-Path $c -ErrorAction SilentlyContinue).Path }
        if ($path -and (Test-Path "$path\bin\java.exe")) {
            $jdkForD8 = $path
            break
        }
    }
    $existing = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match "^jdk-(11|17)" } | Sort-Object Name -Descending
    if (-not $jdkForD8 -and $existing) { $jdkForD8 = $existing[0].FullName }
}

# Signing from Keystore directory under rootDir: rootDir/Keystore/
# Official: Keystore/module_signing/signing-metadata.json, private/official-keystore.p12, private/signing-secret.dpapi
# Dev:      Keystore/dev_signing/signing-metadata.json, private/dev-keystore.p12, private/signing-secret.dpapi
$officialSigningRoot = Join-Path $rootDir "Keystore\module_signing"
$officialPrivateDir = Join-Path $officialSigningRoot "private"
$officialMetadataPath = Join-Path $officialSigningRoot "signing-metadata.json"
$officialKeystorePath = Join-Path $officialPrivateDir "official-keystore.p12"
$officialSecretPath = Join-Path $officialPrivateDir "signing-secret.dpapi"

$devSigningRoot = Join-Path $rootDir "Keystore\dev_signing"
$devPrivateDir = Join-Path $devSigningRoot "private"
$devMetadataPath = Join-Path $devSigningRoot "signing-metadata.json"
$devKeystorePath = Join-Path $devPrivateDir "dev-keystore.p12"
$devSecretPath = Join-Path $devPrivateDir "signing-secret.dpapi"

if (-not [string]::IsNullOrWhiteSpace($DevKey)) {
    $altDevRoot = if ([System.IO.Path]::IsPathRooted($DevKey)) { $DevKey } else { Join-Path $rootDir $DevKey }
    if (-not (Test-Path $altDevRoot)) { throw "DevKey path not found: $altDevRoot" }
    $devSigningRoot = $altDevRoot
    $devPrivateDir = Join-Path $devSigningRoot "private"
    $devMetadataPath = Join-Path $devSigningRoot "signing-metadata.json"
    # Auto-detect keystore filename in private dir
    $devKeystoreFile = Get-ChildItem (Join-Path $devPrivateDir "*.p12") -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($devKeystoreFile) { $devKeystorePath = $devKeystoreFile.FullName }
    else { throw "No .p12 keystore found in $devPrivateDir" }
    # Try DPAPI secret first; if not found, look for plaintext password file
    $devSecretFile = Get-ChildItem (Join-Path $devPrivateDir "*signing-secret.dpapi") -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($devSecretFile) {
        $devSecretPath = $devSecretFile.FullName
    } else {
        # Check for plaintext password file (for new developers who haven't set up DPAPI)
        $passwordFile = Join-Path $devPrivateDir "signing-password.txt"
        if (Test-Path $passwordFile) {
            $script:altDevPassword = (Get-Content $passwordFile -Raw).Trim()
            $devSecretPath = $null  # Signal to use plaintext password
        } else {
            throw "No signing-secret.dpapi or signing-password.txt found in $devPrivateDir"
        }
    }
    Write-Host "  Using alternate dev key: $devSigningRoot" -ForegroundColor Cyan
}

if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir -Force | Out-Null }
if (-not (Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir -Force | Out-Null }

$logPath = Join-Path $logsDir ("pack_modules_" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".log")
try { Start-Transcript -Path $logPath -Force | Out-Null } catch {}

function Get-RequiredTool($path, $name) {
    if (-not (Test-Path $path)) {
        throw "$name not found: $path"
    }
    return $path
}

function Find-SoFiles($soName, $moduleId) {
    $results = @{}

    # Module's own native build output (standalone NDK build)
    $moduleNativeBuild = Join-Path $srcBase "$moduleId\native\build\libs"
    if (Test-Path $moduleNativeBuild) {
        foreach ($abi in $abis) {
            $path = Join-Path $moduleNativeBuild "$abi\$soName"
            if (Test-Path $path) {
                $results[$abi] = $path
            }
        }
    }

    return $results
}

# Tess-two (Tesseract) for ocr_tesseract: find AAR and return path to extracted dir, or $null
function Get-TessTwoExtractDir() {
    $extract = Join-Path $env:TEMP "tess_two_aar"
    function Expand-AarTo($aarPath) {
        if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
        $zipPath = [System.IO.Path]::ChangeExtension($aarPath, ".zip")
        Copy-Item -Path $aarPath -Destination $zipPath -Force
        try { Expand-Archive -Path $zipPath -DestinationPath $extract -Force }
        finally { Remove-Item $zipPath -Force -ErrorAction SilentlyContinue }
    }
    $aarLocal = Join-Path $srcBase "ocr_tesseract\libs\tess-two.aar"
    if (Test-Path $aarLocal) {
        if (-not (Test-Path $extract) -or (Get-Item $aarLocal).LastWriteTime -gt (Get-Item $extract -ErrorAction SilentlyContinue).LastWriteTime) {
            Expand-AarTo $aarLocal
        }
        return $extract
    }
    $gradleModules = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.rmtheis\tess-two"
    if (Test-Path $gradleModules) {
        $verDir = Get-ChildItem $gradleModules -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($verDir) {
            $aar = Get-ChildItem $verDir.FullName -Filter "*.aar" -Recurse | Select-Object -First 1
            if ($aar) {
                if (-not (Test-Path $extract) -or $aar.LastWriteTime -gt (Get-Item $extract -ErrorAction SilentlyContinue).LastWriteTime) {
                    Expand-AarTo $aar.FullName
                }
                return $extract
            }
        }
    }
    return $null
}

function Find-TessTwoSoFiles($soName) {
    $results = @{}
    $extract = Get-TessTwoExtractDir
    if (-not $extract) { return $results }
    $jniDir = Join-Path $extract "jni"
    if (-not (Test-Path $jniDir)) { return $results }
    foreach ($abi in $abis) {
        $path = Join-Path $jniDir "$abi\$soName"
        if (Test-Path $path) { $results[$abi] = $path }
    }
    return $results
}

# ML Kit .so from Gradle transforms cache (for OCR module only)
function Find-MlKitSoFiles() {
    $results = @{}
    $name = "libmlkit_google_ocr_pipeline.so"
    $firstSo = Get-ChildItem -Path $gradleCacheTransforms -Recurse -Filter $name -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $firstSo) { return $results }
    $jniDir = $firstSo.Directory.FullName   # e.g. .../jni/arm64-v8a
    $baseJni = $firstSo.Directory.Parent.FullName  # .../jni
    foreach ($abi in $abis) {
        $path = Join-Path $baseJni "$abi\$name"
        if (Test-Path $path) { $results[$abi] = $path }
    }
    return $results
}

function Get-SigningConfig($metaPath, $secretFile, $keystoreFile, $label) {
    if (-not (Test-Path $metaPath)) {
        throw "$label signing metadata missing: $metaPath"
    }
    if (-not (Test-Path $keystoreFile)) {
        throw "$label keystore missing: $keystoreFile"
    }
    $metadata = Get-Content $metaPath -Raw | ConvertFrom-Json

    # Determine password source: DPAPI or plaintext file or alt dev password
    $password = $null
    if ($label -eq "Developer" -and $script:altDevPassword) {
        $password = $script:altDevPassword
    } elseif ($secretFile -and (Test-Path $secretFile)) {
        $protectedBase64 = Get-Content $secretFile -Raw
        $protectedBytes = [Convert]::FromBase64String($protectedBase64.Trim())
        $bytes = [System.Security.Cryptography.ProtectedData]::Unprotect(
            $protectedBytes,
            $null,
            [System.Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        $password = [System.Text.Encoding]::UTF8.GetString($bytes)
    } else {
        # Fallback: check for plaintext password file in same directory as keystore
        $keystoreDir = Split-Path $keystoreFile -Parent
        $plainFile = Join-Path $keystoreDir "signing-password.txt"
        if (Test-Path $plainFile) {
            $password = (Get-Content $plainFile -Raw).Trim()
        } else {
            throw "$label signing password not found. Provide signing-secret.dpapi or signing-password.txt"
        }
    }

    return @{
        Alias = $metadata.alias
        Password = $password
        Keystore = $keystoreFile
    }
}

function Build-PluginJar($modId, $destinationJar) {
    $srcDir = Join-Path $srcBase "$modId\plugin_src"
    if (-not (Test-Path $srcDir)) {
        Write-Host "  ! plugin source missing for $modId at $srcDir" -ForegroundColor Red
        return $false
    }
    $javaFiles = Get-ChildItem $srcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
    if (-not $javaFiles) {
        Write-Host "  ! no java plugin files for $modId" -ForegroundColor Red
        return $false
    }

    $workDir = Join-Path $env:TEMP "plugin_build_$modId"
    $classesDir = Join-Path $workDir "classes"
    $dexDir = Join-Path $workDir "dex"
    $classesJar = Join-Path $workDir "classes.jar"
    $pluginZip = Join-Path $workDir "plugin.zip"

    if (Test-Path $workDir) { Remove-Item -Recurse -Force $workDir }
    New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
    New-Item -ItemType Directory -Path $dexDir -Force | Out-Null

    # Build classpath: android SDK + shared API (ModulePlugin interface). OCR uses reflection, no ML Kit at compile time.
    $compileCp = "$androidJar;$sharedApiDir"

    & $javac -encoding UTF-8 -cp $compileCp -d $classesDir $javaFiles
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ! javac failed for $modId" -ForegroundColor Red
        return $false
    }

    & $jarExe cf $classesJar -C $classesDir .
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ! jar failed for $modId" -ForegroundColor Red
        return $false
    }

    # d8 requires Java 11+. Use JDK 11+ for this step only (save/restore JAVA_HOME).
    $prevJAVA_HOME = $env:JAVA_HOME
    if ($script:jdkForD8) {
        $env:JAVA_HOME = $script:jdkForD8
        Write-Host "  d8 using JAVA_HOME=$env:JAVA_HOME (Java 11+ required)" -ForegroundColor DarkGray
    } else {
        Write-Host "  ! d8 needs Java 11+. Set JAVA_HOME to JDK 11 or 17, or install one." -ForegroundColor Red
        return $false
    }
    # d8: convert to DEX. For ocr merge ML Kit JARs; for ocr_tesseract merge tess-two classes.jar.
    $tessTwoJar = $null
    if ($modId -eq "ocr_tesseract") {
        $tessExtract = Get-TessTwoExtractDir
        if ($tessExtract) {
            $tessTwoJar = Join-Path $tessExtract "classes.jar"
            if (-not (Test-Path $tessTwoJar)) { $tessTwoJar = $null }
        }
        if (-not $tessTwoJar) {
            Write-Host "  ! ocr_tesseract: put tess-two.aar in source/ocr_tesseract/libs/ or add com.rmtheis:tess-two to Gradle" -ForegroundColor Red
            if ($null -ne $prevJAVA_HOME) { $env:JAVA_HOME = $prevJAVA_HOME }
            return $false
        }
        Write-Host "  + merging tess-two classes" -ForegroundColor DarkGray
    }
    if ($modId -eq "ocr" -and $mlkitJars.Count -eq 0) {
        Write-Host "  ! ocr: no ML Kit JARs found. Per MODULE_DEPENDENCIES.md the plugin must bundle ML Kit. Build an app that depends on ML Kit (e.g. implementation 'com.google.mlkit:text-recognition-chinese:16.0.1') once so Gradle caches the JARs, then re-run pack." -ForegroundColor Red
        if ($null -ne $prevJAVA_HOME) { $env:JAVA_HOME = $prevJAVA_HOME }
        return $false
    }
    if ($modId -eq "ocr" -and $mlkitJars.Count -gt 0) {
        Write-Host "  + merging ML Kit JARs ($($mlkitJars.Count) jars)" -ForegroundColor DarkGray
    }
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($modId -eq "ocr" -and $mlkitJars.Count -gt 0) {
            & $d8 --lib $androidJar --output $dexDir $classesJar $mlkitJars 2>&1 | ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) { Write-Host $_.ToString() -ForegroundColor DarkYellow }
                else { Write-Host $_ }
            }
        } elseif ($modId -eq "ocr_tesseract" -and $tessTwoJar) {
            & $d8 --lib $androidJar --output $dexDir $classesJar $tessTwoJar 2>&1 | ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) { Write-Host $_.ToString() -ForegroundColor DarkYellow }
                else { Write-Host $_ }
            }
        } else {
            & $d8 --lib $androidJar --output $dexDir $classesJar 2>&1 | ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) { Write-Host $_.ToString() -ForegroundColor DarkYellow }
                else { Write-Host $_ }
            }
        }
    } finally {
        if ($null -ne $prevJAVA_HOME) { $env:JAVA_HOME = $prevJAVA_HOME } else { Remove-Item env:JAVA_HOME -ErrorAction SilentlyContinue }
        $ErrorActionPreference = $prevEAP
    }
    $d8Exit = $LASTEXITCODE
    if ($d8Exit -ne 0) {
        Write-Host "  ! d8 failed for $modId (exit code $d8Exit)" -ForegroundColor Red
        return $false
    }

    if (Test-Path $pluginZip) { Remove-Item $pluginZip }
    $dexFiles = Get-ChildItem $dexDir -Filter "*.dex" | Select-Object -ExpandProperty FullName
    if (-not $dexFiles) { Write-Host "  ! no .dex output for $modId" -ForegroundColor Red; return $false }
    Compress-Archive -Path $dexFiles -DestinationPath $pluginZip -Force
    if (Test-Path $destinationJar) { Remove-Item $destinationJar }
    Move-Item $pluginZip $destinationJar -Force
    return $true
}

function Sign-ModuleFile($unsignedZip, $signedFile) {
    $devConfig = Get-SigningConfig $devMetadataPath $devSecretPath $devKeystorePath "Developer"
    $officialConfig = Get-SigningConfig $officialMetadataPath $officialSecretPath $officialKeystorePath "Official"

    $devSignedTemp = Join-Path $env:TEMP ("dev_signed_" + [System.IO.Path]::GetFileName($signedFile))
    if (Test-Path $devSignedTemp) { Remove-Item $devSignedTemp -Force }
    if (Test-Path $signedFile) { Remove-Item $signedFile -Force }

    # Use Java 11+ for jarsigner so PKCS12 keystores from keytool (JDK 11/17) load correctly; Java 8 jarsigner can fail with "parseAlgParameters failed"
    $prevJAVA_HOME = $env:JAVA_HOME
    if ($script:jdkForD8) { $env:JAVA_HOME = $script:jdkForD8 }
    $signingJarsigner = "$env:JAVA_HOME\bin\jarsigner.exe"
    if (-not (Test-Path $signingJarsigner)) { $signingJarsigner = $jarsigner }

    & $signingJarsigner `
        -keystore $devConfig.Keystore `
        -storetype PKCS12 `
        -storepass $devConfig.Password `
        -keypass $devConfig.Password `
        -sigalg SHA256withRSA `
        -digestalg SHA-256 `
        -sigfile DEV `
        -signedjar $devSignedTemp `
        $unsignedZip `
        $devConfig.Alias

    if ($LASTEXITCODE -ne 0) {
        if ($script:jdkForD8) { $env:JAVA_HOME = $prevJAVA_HOME }
        throw "Developer signing failed for $signedFile"
    }

    & $signingJarsigner `
        -keystore $officialConfig.Keystore `
        -storetype PKCS12 `
        -storepass $officialConfig.Password `
        -keypass $officialConfig.Password `
        -sigalg SHA256withRSA `
        -digestalg SHA-256 `
        -sigfile OFFICIAL `
        -signedjar $signedFile `
        $devSignedTemp `
        $officialConfig.Alias

    if ($LASTEXITCODE -ne 0) {
        if ($script:jdkForD8) { $env:JAVA_HOME = $prevJAVA_HOME }
        throw "Official signing failed for $signedFile"
    }

    Remove-Item $devSignedTemp -Force -ErrorAction SilentlyContinue

    & $signingJarsigner -verify $signedFile | Out-Null
    if ($LASTEXITCODE -ne 0) {
        if ($script:jdkForD8) { $env:JAVA_HOME = $prevJAVA_HOME }
        throw "jarsigner verification failed for $signedFile"
    }
    if ($script:jdkForD8 -and $null -ne $prevJAVA_HOME) { $env:JAVA_HOME = $prevJAVA_HOME }
    elseif ($script:jdkForD8) { Remove-Item env:JAVA_HOME -ErrorAction SilentlyContinue }
}

Get-RequiredTool $javac "javac.exe" | Out-Null
Get-RequiredTool $jarExe "jar.exe" | Out-Null
Get-RequiredTool $d8 "d8.bat" | Out-Null
if (-not $SkipSigning) {
    Get-RequiredTool $jarsigner "jarsigner.exe" | Out-Null
}

$moduleFilter = @()
if (-not [string]::IsNullOrWhiteSpace($Modules)) {
    $moduleFilter = $Modules -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
}

Write-Host "=========================================="
Write-Host "  Signed Module Packaging Tool"
Write-Host "=========================================="
Write-Host "  Source: $srcBase"
Write-Host "  Output: $distDir"
if ($moduleFilter.Count -gt 0) { Write-Host "  Modules: $($moduleFilter -join ', ')" }
Write-Host "  Log:    $logPath"
Write-Host ""

$moduleDirs = Get-ChildItem $srcBase -Directory | Where-Object { $_.Name -ne "shared_api" }
if ($moduleFilter.Count -gt 0) {
    $moduleDirs = $moduleDirs | Where-Object { $moduleFilter -contains $_.Name }
    $missing = $moduleFilter | Where-Object { $moduleDirs.Name -notcontains $_ }
    if ($missing) {
        Write-Host "  Warning: no source dir for: $($missing -join ', ')" -ForegroundColor Yellow
    }
}
if ($moduleDirs.Count -eq 0) {
    Write-Host "  No module directories to pack"
    exit 0
}

$builtModules = @{}   # modId -> version, for modules.json update

foreach ($modDir in $moduleDirs) {
    $modId = $modDir.Name
    $manifestPath = Join-Path $modDir.FullName "manifest.json"

    if (-not (Test-Path $manifestPath)) {
        Write-Host "  SKIP $modId (no manifest.json)" -ForegroundColor Yellow
        continue
    }

    Write-Host "Packaging: $modId ..." -ForegroundColor Yellow

    $manifestText = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8)
    $manifest = $manifestText | ConvertFrom-Json
    $version = if ($manifest.version) { [string]$manifest.version } else { "1.0" }
    $soLibs = @()
    if ($manifest.soLibraries) { $soLibs = @($manifest.soLibraries) }

    $tempDir = Join-Path $env:TEMP "mod_pack_$modId"
    $unsignedZip = Join-Path $env:TEMP "$modId-$version-unsigned.zip"
    if (Test-Path $tempDir) { Remove-Item -Recurse -Force $tempDir }
    if (Test-Path $unsignedZip) { Remove-Item -Force $unsignedZip }
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    Copy-Item $manifestPath "$tempDir\manifest.json"
    if (-not $SkipSigning -and (Test-Path $devMetadataPath)) {
        $devMeta = Get-Content $devMetadataPath -Raw | ConvertFrom-Json
        $devFp = $devMeta.fingerprintSha256
        $tmpManifestPath = "$tempDir\manifest.json"
        $tmpManifestText = [System.IO.File]::ReadAllText($tmpManifestPath, [System.Text.Encoding]::UTF8)
        $tmpManifest = $tmpManifestText | ConvertFrom-Json
        $tmpManifest | Add-Member -NotePropertyName "devCertFingerprint" -NotePropertyValue $devFp -Force
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($tmpManifestPath, ($tmpManifest | ConvertTo-Json -Depth 10), $utf8NoBom)
        Write-Host "  + manifest.json (devCertFingerprint: $($devFp.Substring(0,16))...)"
    } else {
        Write-Host "  + manifest.json"
    }

    $h5Source = Join-Path $modDir.FullName "index.html"
    if (Test-Path $h5Source) {
        Copy-Item $h5Source "$tempDir\index.html"
        Write-Host "  + index.html"
    } else {
        Write-Host "  ! index.html NOT FOUND" -ForegroundColor Red
    }

    $cssSource = Join-Path $srcBase "common.css"
    if (Test-Path $cssSource) {
        Copy-Item $cssSource "$tempDir\common.css"
        Write-Host "  + common.css"
    }

    $pluginJarTarget = Join-Path $tempDir "plugin.jar"
    if (Build-PluginJar $modId $pluginJarTarget) {
        Write-Host "  + plugin.jar"
    } else {
        throw "plugin.jar build failed for $modId"
    }

    foreach ($soName in $soLibs) {
        if ($modId -eq "ocr" -and $soName -eq "libmlkit_google_ocr_pipeline.so") {
            $soFiles = Find-MlKitSoFiles
        } elseif ($modId -eq "ocr_tesseract" -and ($soName -eq "liblept.so" -or $soName -eq "libtess.so")) {
            $soFiles = Find-TessTwoSoFiles $soName
        } else {
            $soFiles = Find-SoFiles $soName $modId
        }
        if ($soFiles.Count -eq 0) {
            if ($modId -eq "ocr_tesseract") {
                throw "$soName not found (put tess-two.aar in source/ocr_tesseract/libs/ or add com.rmtheis:tess-two to Gradle)"
            }
            throw "$soName not found (build first? or for OCR ensure Gradle cache has text-recognition-bundled-common)"
        }
        foreach ($abi in $soFiles.Keys) {
            $libDir = "$tempDir\libs\$abi"
            if (-not (Test-Path $libDir)) { New-Item -ItemType Directory -Path $libDir -Force | Out-Null }
            Copy-Item $soFiles[$abi] "$libDir\$soName"
            Write-Host "  + libs/$abi/$soName"
        }
    }

    $modFile = Join-Path $distDir "$modId.mod"
    if (Test-Path $modFile) { Remove-Item $modFile -Force -ErrorAction SilentlyContinue }

    Compress-Archive -Path "$tempDir\*" -DestinationPath $unsignedZip -Force
    if ($SkipSigning) {
        Move-Item $unsignedZip $modFile -Force
    } else {
        Sign-ModuleFile $unsignedZip $modFile
        Remove-Item $unsignedZip -Force
    }

    $builtModules[$modId] = $version
    $sizeKB = [math]::Round((Get-Item $modFile).Length / 1KB, 1)
    Write-Host "  => $modId.mod ($sizeKB KB)" -ForegroundColor Green

    Remove-Item -Recurse -Force $tempDir
    Write-Host ""
}

# Update modules.json (updated_at, versions, auto-add new modules)
if ($builtModules.Count -gt 0 -and (Test-Path $modulesJsonPath)) {
    try {
        $jsonText = [System.IO.File]::ReadAllText($modulesJsonPath, [System.Text.Encoding]::UTF8)
        $index = $jsonText | ConvertFrom-Json
        $index.updated_at = Get-Date -Format "yyyy-MM-dd"
        $existingIds = @($index.modules | ForEach-Object { $_.id })

        foreach ($modId in $builtModules.Keys) {
            $ver = $builtModules[$modId]
            $modEntry = $index.modules | Where-Object { $_.id -eq $modId } | Select-Object -First 1
            if ($modEntry) {
                $modEntry.version = $ver
            } else {
                # Auto-add new module from manifest
                $srcManifest = Join-Path $srcBase "$modId\manifest.json"
                if (Test-Path $srcManifest) {
                    $mText = [System.IO.File]::ReadAllText($srcManifest, [System.Text.Encoding]::UTF8)
                    $m = $mText | ConvertFrom-Json
                    $nameZh = if ($m.name) { [string]$m.name } else { $modId }
                    $nameEn = if ($m.name_en) { [string]$m.name_en } else { $nameZh }
                    $descZh = if ($m.description) { [string]$m.description } else { "" }
                    $descEn = if ($m.description_en) { [string]$m.description_en } else { $descZh }
                    $icon = if ($m.icon) { [string]$m.icon } else { "extension" }
                    $devName = if ($m.developer -and $m.developer.name) { [string]$m.developer.name } else { "PandaGenie Official" }

                    $perms = @()
                    if ($m.permissions) { $perms = @($m.permissions) }
                    $caps = @()
                    if ($m.capabilities) { $caps = @($m.capabilities) }
                    $apis = @()
                    if ($m.apis) { $apis = @($m.apis) }

                    $newEntry = [PSCustomObject]@{
                        id = $modId
                        name = [PSCustomObject]@{ zh = $nameZh; en = $nameEn }
                        description = [PSCustomObject]@{ zh = $descZh; en = $descEn }
                        version = $ver
                        icon = $icon
                        filename = "$modId.mod"
                        download_url = @(
                            "https://cf.pandagenie.ai/modules/download/$modId",
                            "https://github.com/Rorschach123/PandaGenieSource/raw/main/modules/$modId.mod"
                        )
                        developer = [PSCustomObject]@{ name = $devName }
                        permissions = $perms
                        capabilities = $caps
                        apis = $apis
                        api_count = $apis.Count
                    }
                    $index.modules += $newEntry
                    Write-Host "  + Auto-added $modId to modules.json" -ForegroundColor Green
                } else {
                    Write-Host "  ! Cannot auto-add ${modId}: no manifest at ${srcManifest}" -ForegroundColor Yellow
                }
            }
        }
        $utf8NoBom2 = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($modulesJsonPath, ($index | ConvertTo-Json -Depth 20), $utf8NoBom2)
        Write-Host "  modules.json updated (updated_at, versions)." -ForegroundColor Cyan
    } catch {
        Write-Host "  Warning: could not update modules.json: $_" -ForegroundColor Yellow
    }
}

Write-Host "=========================================="
Write-Host "  Done! .mod files are in: $distDir"
Write-Host "=========================================="

try { Stop-Transcript | Out-Null } catch {}
