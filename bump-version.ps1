# Usage: .\bump-version.ps1 3.1.0
# Bumps versionName and increments versionCode by 1 in app/build.gradle.kts

param([Parameter(Mandatory)][string]$NewVersion)

$file    = "$PSScriptRoot\app\build.gradle.kts"
$content = Get-Content $file -Raw

$null    = $content -match 'versionCode = (\d+)'
$newCode = [int]$Matches[1] + 1

$content = $content -replace 'versionCode = \d+',     "versionCode = $newCode"
$content = $content -replace 'versionName = "[^"]+"', "versionName = `"$NewVersion`""

Set-Content $file $content -NoNewline
Write-Host "Bumped to versionCode=$newCode versionName=$NewVersion"
