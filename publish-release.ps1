param(
    [switch]$SkipTests,
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

$status = git status --porcelain --untracked-files=no
if ($LASTEXITCODE -ne 0) { throw "Could not check the working tree" }
if ($status -and -not $ValidateOnly) {
    throw "Commit and push the source changes before publishing."
}

$gradleConfig = Get-Content -LiteralPath app/build.gradle.kts -Raw -Encoding UTF8
$version = [regex]::Match($gradleConfig, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"').Groups[1].Value
$versionCode = [regex]::Match($gradleConfig, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $version -or -not $versionCode) { throw "Could not read the APK version from Gradle" }
$tag = "v$version"
$repository = "apple050620312/MoQPublisher"
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
    & $gradle assembleRelease assembleOffline testDebugUnitTest lintRelease --no-configuration-cache
    if ($LASTEXITCODE -ne 0) { throw "Gradle build or tests failed" }
}

$sdk = $null
if (Test-Path -LiteralPath local.properties) {
    $local = Get-Content -LiteralPath local.properties -Raw -Encoding UTF8
    $sdk = [regex]::Match($local, '(?m)^sdk\.dir\s*=\s*([^\r\n]+)').Groups[1].Value.Trim()
    $sdk = $sdk.Replace('\\', '\').Replace('\:', ':')
}
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw "Set sdk.dir in local.properties or ANDROID_HOME to your Android SDK." }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk "build-tools") -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat')) } |
    Sort-Object { [Version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "Install Android SDK Build Tools before publishing." }
$aapt = Join-Path $buildTools.FullName "aapt2.exe"
$signer = Join-Path $buildTools.FullName "apksigner.bat"
$zipalign = Join-Path $buildTools.FullName "zipalign.exe"
# Public certificate SHA-256 from the existing MoQ Publisher sideload releases.
$signingCertificate = '26adf81d995180fd487cee1d58da1c8fa4018357a95907d6af6e1a9eb4636122'
foreach ($file in @($apk, $offlineApk)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "APK was not produced: $file" }
    $badging = & $aapt dump badging $file
    if ($LASTEXITCODE -ne 0) { throw "Could not read the APK manifest: $file" }
    $package = $badging | Select-String '^package:'
    if ($package -notmatch "name='com\.sangege\.moqpublisher'.*versionCode='$versionCode'.*versionName='$([regex]::Escape($version))'") {
        throw "APK package or version does not match Gradle: $file"
    }
    $signature = & $signer verify --verbose --print-certs $file
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $file" }
    if (-not ($signature -match "certificate SHA-256 digest: $signingCertificate$") -or
        -not ($signature -match '\(APK Signature Scheme v2\): true$')) {
        throw "APK cannot update the existing MoQ Publisher installation: $file"
    }
    & $zipalign -c -P 16 4 $file
    if ($LASTEXITCODE -ne 0) { throw "APK alignment verification failed: $file" }
    Write-Output "Verified $file"
}
if ($ValidateOnly) { return }

$commit = git rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw "Could not determine HEAD" }
$remoteMain = git ls-remote origin refs/heads/main
if ($LASTEXITCODE -ne 0 -or ($remoteMain -split '\s+')[0] -ne $commit) {
    throw "Push this release commit to origin/main before publishing."
}

New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item -LiteralPath $apk -Destination $releaseApk -Force
Copy-Item -LiteralPath $offlineApk -Destination $offlineReleaseApk -Force
$hash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText($checksum, "$hash  $apkName" + "`n", [System.Text.UTF8Encoding]::new($false))
$offlineHash = (Get-FileHash -LiteralPath $offlineReleaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText($offlineChecksum, "$offlineHash  $offlineApkName" + "`n", [System.Text.UTF8Encoding]::new($false))

if (-not (git tag --list $tag)) {
    git tag -a $tag -m "Release MoQ Publisher $version"
    if ($LASTEXITCODE -ne 0) { throw "Git tag creation failed" }
}
$tagCommit = git rev-list -1 $tag --
if ($LASTEXITCODE -ne 0 -or $tagCommit -ne $commit) { throw "The existing tag points to a different commit: $tag" }
git push origin "refs/tags/$tag"
if ($LASTEXITCODE -ne 0) { throw "Git tag push failed" }

gh release create $tag $releaseApk $checksum $offlineReleaseApk $offlineChecksum --repo $repository --verify-tag --title "MoQ Publisher $version" --generate-notes
if ($LASTEXITCODE -ne 0) { throw "GitHub Release upload failed" }
$releaseInfo = gh api "repos/$repository/releases/tags/$tag" | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $releaseInfo.draft -or $releaseInfo.tag_name -ne $tag) { throw "GitHub Release verification failed" }
foreach ($file in @($releaseApk, $checksum, $offlineReleaseApk, $offlineChecksum)) {
    $asset = $releaseInfo.assets | Where-Object name -eq (Split-Path -Leaf $file)
    $expected = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($asset.state -ne "uploaded" -or $asset.digest -ne "sha256:$expected") {
        throw "GitHub asset verification failed: $file"
    }
}
Write-Output "Published ${tag}: $releaseApk"
