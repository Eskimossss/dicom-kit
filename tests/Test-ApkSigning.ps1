# Integration tests against the real Gradle signing preflight and Android SDK.
# Run after a diagnostic or clean Debug/Release build. No signing configuration is changed.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $repo 'scripts\ApkBuild.psm1') -Force
$fixture = Join-Path $repo ('builds\signing-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$fixture\app", "$fixture\scripts" -Force | Out-Null
foreach ($file in @('build.gradle.kts','settings.gradle.kts','gradle.properties','local.properties','gradlew.bat','build-test-apk.ps1')) {
    Copy-Item -LiteralPath (Join-Path $repo $file) -Destination $fixture
}
Copy-Item -LiteralPath (Join-Path $repo 'gradle') -Destination $fixture -Recurse
Copy-Item -LiteralPath (Join-Path $repo 'app\build.gradle.kts') -Destination "$fixture\app"
Copy-Item -LiteralPath (Join-Path $repo 'app\proguard-rules.pro') -Destination "$fixture\app"
Copy-Item -LiteralPath (Join-Path $repo 'scripts\ApkBuild.psm1') -Destination "$fixture\scripts"
$variables = @('KEYSTORE_PATH','KEYSTORE_PASSWORD','KEY_ALIAS','KEY_PASSWORD')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name); [Environment]::SetEnvironmentVariable($name, $null) }
try {
    foreach ($type in @('Release','Both')) {
        $log = Join-Path $fixture "missing-key-$type.log"
        $ErrorActionPreference = 'Continue'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$fixture\build-test-apk.ps1" -AllowDirty -BuildType $type *> $log
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -eq 0 -or (Get-Content $log -Raw) -notmatch 'Release signing unavailable or certificate mismatch') {
            throw "Missing key did not fail correctly: $log"
        }
        if (Test-Path "$fixture\builds") { throw 'Failed preflight unexpectedly published artifacts.' }
        Write-Host "PASS: $type rejects missing release key; no artifacts published."
    }
    $env:KEYSTORE_PATH = Join-Path $env:USERPROFILE '.android\debug.keystore'
    # Public default Android debug credentials, used only to assert rejection.
    $env:KEYSTORE_PASSWORD = 'android'; $env:KEY_ALIAS = 'androiddebugkey'; $env:KEY_PASSWORD = 'android'
    $ErrorActionPreference = 'Continue'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$fixture\build-test-apk.ps1" -AllowDirty -BuildType Release *> "$fixture\debug-key.log"
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -eq 0 -or (Get-Content "$fixture\debug-key.log" -Raw) -notmatch 'Release signing unavailable or certificate mismatch') {
        throw 'Debug key incorrectly accepted as release key.'
    }
    Write-Host 'PASS: Real debug keystore rejected for Release.'
    # Preserve AGP's normal first-build Debug behavior without touching the user's key.
    $previousAndroidHome = $env:ANDROID_USER_HOME
    $env:ANDROID_USER_HOME = Join-Path $fixture '.android'
    Push-Location $fixture
    try {
        if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8' }
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :app:generateDebugApkMetadata --console=plain --no-configuration-cache --no-daemon *> fresh-debug.log
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path '.android\debug.keystore')) { throw 'Fresh standard Debug signing failed.' }
    } finally { Pop-Location; $env:ANDROID_USER_HOME = $previousAndroidHome }
    Write-Host 'PASS: Standard Debug key generation works on a fresh checkout.'
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8' }
    $sdk = [regex]::Match((Get-Content (Join-Path $repo local.properties) -Raw), '(?m)^sdk\.dir=(.+)').Groups[1].Value.Trim().Replace('\:',':').Replace('\\','\')
    $toolsVersion = [regex]::Match((Get-Content (Join-Path $repo 'app\build.gradle.kts') -Raw), 'buildToolsVersion\s*=\s*"([^"]+)"').Groups[1].Value
    $aapt = Join-Path $sdk "build-tools\$toolsVersion\aapt.exe"
    $signer = Join-Path $sdk "build-tools\$toolsVersion\apksigner.bat"
    $debugApk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
    $metadata = Get-Content (Join-Path $repo 'app\build\generated\apkMetadata\debug\assets\dicom-kit-build.json') -Raw | ConvertFrom-Json
    $expected = @{}
    foreach ($key in @('applicationId','versionName','versionCode','buildType','gitBranch','gitHead','gitCommit','gitDirty','certificateSha256')) { $expected[$key] = $metadata.$key }
    function Rejected([scriptblock]$Action, [string]$Pattern) {
        $message = $null
        try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
        if (-not $message -or $message -notmatch $Pattern) { throw "Expected '$Pattern', got '$message'" }
    }
    # Repack a real APK into a fresh ZIP: removes the APK signing block and JAR signatures.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $unsigned = Join-Path $fixture unsigned.apk
    $inputZip = [IO.Compression.ZipFile]::OpenRead($debugApk)
    $outputZip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $inputZip.Entries) {
            if ($entry.FullName -like 'META-INF/*' -or $entry.FullName.EndsWith('/')) { continue }
            $copy = $outputZip.CreateEntry($entry.FullName)
            $from = $entry.Open(); $to = $copy.Open()
            try { $from.CopyTo($to) } finally { $from.Dispose(); $to.Dispose() }
        }
    } finally { $inputZip.Dispose(); $outputZip.Dispose() }
    Rejected { Test-ApkArtifact $unsigned $aapt $signer $expected } 'signature verification failed'
    Write-Host 'PASS: Real unsigned APK rejected by apksigner.'
    $wrongVersion = $expected.Clone(); $wrongVersion.versionCode = 10501
    Rejected { Test-ApkArtifact $debugApk $aapt $signer $wrongVersion } 'package/version metadata mismatch'
    Write-Host 'PASS: Real APK version mismatch rejected.'
    $wrongGit = $expected.Clone(); $wrongGit.gitHead = 'wrong'
    Rejected { Test-ApkArtifact $debugApk $aapt $signer $wrongGit } 'metadata mismatch: gitHead'
    Write-Host 'PASS: Real APK embedded Git mismatch rejected.'
    $release = Join-Path $repo 'app\build\outputs\apk\release\app-release.apk'
    $wrongCertificate = $expected.Clone(); $wrongCertificate.certificateSha256 = Get-ApkCertificate $signer $release
    Rejected { Test-ApkArtifact $debugApk $aapt $signer $wrongCertificate } 'certificate mismatch'
    Write-Host 'PASS: Debug certificate rejected when Release certificate expected.'
    Write-Host "All 8 signing/SDK integration tests passed. Evidence: $fixture"
} finally {
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name]) }
}
exit 0
