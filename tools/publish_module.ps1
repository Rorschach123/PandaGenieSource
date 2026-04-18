param(
    [Parameter(Mandatory=$true)][string]$modPath,
    [string]$changelog = "",
    [string]$changelogZh = "",
    [string]$serverUrl = "https://cf.pandagenie.ai"
)

Write-Host "Uploading: $modPath"
$uploadResp = curl.exe -s -X POST "$serverUrl/submit/upload" -F "modfile=@$modPath"
Write-Host "Upload: $uploadResp"

if ($uploadResp -match '"temp_key"\s*:\s*"([^"]+)"') {
    $tempKey = $Matches[1]
    Write-Host "temp_key: $tempKey"

    $bodyObj = @{
        temp_key = $tempKey
        changelog = $changelog
        changelog_zh = $changelogZh
    }
    $bodyJson = $bodyObj | ConvertTo-Json -Compress
    $tempFile = Join-Path $env:TEMP "pub_body.json"
    [System.IO.File]::WriteAllText($tempFile, $bodyJson, (New-Object System.Text.UTF8Encoding $false))

    $pubResp = curl.exe -s -X POST "$serverUrl/submit/publish" -H "Content-Type: application/json" -d "@$tempFile"
    Write-Host "Publish: $pubResp"
} else {
    Write-Host "ERROR: Could not extract temp_key"
    Write-Host "Response was: $uploadResp"
}
