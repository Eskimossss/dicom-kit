<#
.SYNOPSIS
Build and verify Debug, signed Release, or Both from one Git state.
.PARAMETER AllowDirty
Local diagnostics only; the APK and report retain the dirty marker.
.PARAMETER Force
Deprecated compatibility switch. Previous artifacts are ALWAYS preserved.
.PARAMETER ReferenceReleaseApk
Optional previous release APK; its valid certificate must match the new release.
#>
[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release', 'Both')][string]$BuildType = 'Debug',
    [switch]$AllowDirty,
    [switch]$Force,
    [string]$ReferenceReleaseApk
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'scripts\ApkBuild.psm1') -Force
Push-Location $PSScriptRoot
try {
    if ($Force) { Write-Warning '-Force is deprecated: differing artifacts are always archived.' }
    if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Android\openjdk\jdk-21.0.8')) {
        $env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
    }
    $before = Get-ApkSourceState
    if ($before.Dirty -and -not $AllowDirty) {
        throw 'Working tree must be clean. Commit changes first; -AllowDirty is for local diagnostics only.'
    }
    if ($before.Dirty) { Write-Warning 'Local diagnostic build: dirty source tree.' }
    $configuration = Get-Content 'app\build.gradle.kts' -Raw
    $version = [regex]::Match($configuration, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $code = [regex]::Match($configuration, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    if (-not $version -or -not $code) { throw 'Cannot read versionName/versionCode.' }
    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk -and (Test-Path 'local.properties')) {
        $sdk = [regex]::Match((Get-Content local.properties -Raw), '(?m)^sdk\.dir=(.+)').Groups[1].Value.Trim().Replace('\:', ':').Replace('\\', '\')
    }
    if (-not $sdk) { throw 'Android SDK missing: set ANDROID_SDK_ROOT or sdk.dir in local.properties.' }
    $buildTools = [regex]::Match($configuration, 'buildToolsVersion\s*=\s*"([^"]+)"').Groups[1].Value
    $aapt = Join-Path $sdk "build-tools\$buildTools\aapt.exe"
    $apksigner = Join-Path $sdk "build-tools\$buildTools\apksigner.bat"
    foreach ($tool in @($aapt, $apksigner, '.\gradlew.bat')) {
        if (-not (Test-Path -LiteralPath $tool)) { throw "Required tool missing: $tool" }
    }
    $types = if ($BuildType -eq 'Both') { @('debug', 'release') } else { @($BuildType.ToLowerInvariant()) }
    $expectedCommit = $before.ShortHead
    if ($before.Dirty) { $expectedCommit += '-dirty' }
    $expected = @{
        applicationId = 'ru.medeng.dicomtool'; versionName = $version; versionCode = [int]$code
        gitBranch = $before.Branch; gitHead = $before.Head; gitCommit = $expectedCommit; gitDirty = $before.Dirty
    }
    # Gradle reads secrets itself; none pass on command lines.
    $preflight = @($types | ForEach-Object { ':app:generate' + (Get-Culture).TextInfo.ToTitleCase($_) + 'ApkMetadata' })
    Invoke-ApkGradle $preflight
    $certificates = @{}
    foreach ($type in $types) {
        $generated = Get-Content "app\build\generated\apkMetadata\$type\assets\dicom-kit-build.json" -Raw | ConvertFrom-Json
        Assert-ApkMetadata $generated ($expected + @{ buildType = $type })
        $certificates[$type] = $generated.certificateSha256
    }
    if ($ReferenceReleaseApk -and $types -contains 'release') {
        $referenceCertificate = Get-ApkCertificate $apksigner $ReferenceReleaseApk
        if ($referenceCertificate -ne $certificates.release) { throw 'Previous release certificate differs. Stop and request a signing/migration decision.' }
    }
    $tasks = @($types | ForEach-Object { ':app:assemble' + (Get-Culture).TextInfo.ToTitleCase($_) })
    Invoke-ApkGradle $tasks
    Assert-ApkSourceState $before (Get-ApkSourceState)
    $verified = @()
    foreach ($type in $types) {
        $source = Join-Path $PSScriptRoot "app\build\outputs\apk\$type\app-$type.apk"
        $verified += Test-ApkArtifact $source $aapt $apksigner ($expected + @{ buildType = $type; certificateSha256 = $certificates[$type] })
    }
    # Validate every requested variant before publishing any result.
    Assert-ApkSourceState $before (Get-ApkSourceState)
    foreach ($result in $verified) {
        $target = Join-Path $PSScriptRoot "builds\DICOM-Kit-$version-$($result.buildType).apk"
        Save-ApkArtifact $result $target
        Write-Host ($result | ConvertTo-Json -Depth 5)
        Write-Host "Saved: $target"
    }
    Assert-ApkSourceState $before (Get-ApkSourceState)
    Write-Host "SUCCESS: $BuildType; $version ($code); Git $expectedCommit"
} catch {
    Write-Host "BUILD FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}
exit 0
