param(
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

if ((git status --porcelain) -and -not $SkipTests) {
    throw "Working tree is not clean. Commit and push source changes before publishing a release."
}

$version = [regex]::Match((Get-Content app/build.gradle.kts -Raw), 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"').Groups[1].Value
if ([string]::IsNullOrWhiteSpace($version)) { throw "Could not read versionName from app/build.gradle.kts" }
$tag = "v$version"
$apkName = "MoQPublisher-$version-release.apk"
$offlineApkName = "MoQPublisher-$version-offline.apk"
$apk = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
$offlineApk = Join-Path $repoRoot "app\build\outputs\apk\offline\app-offline.apk"
$dist = Join-Path $repoRoot "dist"
$releaseApk = Join-Path $dist $apkName
$checksum = "$releaseApk.sha256"
$offlineReleaseApk = Join-Path $dist $offlineApkName
$offlineChecksum = "$offlineReleaseApk.sha256"
$gradle = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat"
if (-not (Test-Path $gradle)) { $gradle = Join-Path $repoRoot "gradlew.bat" }

if (-not $SkipTests) {
    & $gradle assembleRelease assembleOffline testDebugUnitTest --no-configuration-cache
    if ($LASTEXITCODE -ne 0) { throw "Gradle build or tests failed" }
}
if (-not (Test-Path $apk)) { throw "Release APK was not produced: $apk" }
if (-not (Test-Path $offlineApk)) { throw "Offline APK was not produced: $offlineApk" }

New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item -LiteralPath $apk -Destination $releaseApk -Force
Copy-Item -LiteralPath $offlineApk -Destination $offlineReleaseApk -Force
$hash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksum -Value "$hash  $apkName" -Encoding UTF8
$offlineHash = (Get-FileHash -LiteralPath $offlineReleaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $offlineChecksum -Value "$offlineHash  $offlineApkName" -Encoding UTF8

if (-not (git tag --list $tag)) {
    git tag -a $tag -m "Release MoQ Publisher $version"
    git push origin $tag
}

gh release create $tag $releaseApk $checksum $offlineReleaseApk $offlineChecksum --repo apple050620312/MoQPublisher --title "MoQ Publisher $version" --generate-notes
if ($LASTEXITCODE -ne 0) { throw "GitHub Release upload failed" }
Write-Output "Published ${tag}: $releaseApk"
