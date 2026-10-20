# Dependency-free PowerShell regression tests. All fixtures use synthetic bytes, no medical data.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot '..\scripts\ApkBuild.psm1') -Force
$script:passed = 0
function Check([string]$Name, [scriptblock]$Action) {
    & $Action
    $script:passed++
    Write-Host "PASS: $Name"
}
function MustFail([scriptblock]$Action, [string]$Pattern) {
    $message = $null
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    if (-not $message -or $message -notmatch $Pattern) { throw "Expected failure '$Pattern', got '$message'" }
}
function Equal($Actual, $Expected) {
    if ($Actual -cne $Expected) { throw "Expected '$Expected', got '$Actual'" }
}
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('dicom-apk-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $expected = @{applicationId='ru.medeng.dicomtool';versionName='1.5.0-alpha.2';versionCode=10502;buildType='debug';gitHead='abc';gitBranch='test';gitDirty=$false}
    Check 'Matching metadata accepted' { Assert-ApkMetadata ([pscustomobject]$expected) $expected }
    foreach ($key in $expected.Keys) {
        Check "Mismatched metadata rejected: $key" {
            $changed = $expected.Clone(); $changed[$key] = 'wrong'
            MustFail { Assert-ApkMetadata ([pscustomobject]$changed) $expected } 'metadata mismatch'
        }
    }
    Check 'Missing metadata rejected' { MustFail { Assert-ApkMetadata ([pscustomobject]@{}) $expected } 'metadata mismatch' }
    $badging = "package: name='ru.medeng.dicomtool' versionCode='10502' versionName='1.5.0-alpha.2'`napplication-debuggable"
    Check 'Valid Debug manifest accepted' { Assert-ApkBadging $badging $expected }
    foreach ($replacement in @(@('ru.medeng.dicomtool','wrong.package'), @('10502','10501'), @('alpha.2','alpha.1'))) {
        Check "Bad manifest rejected: $($replacement[0])" {
            MustFail { Assert-ApkBadging $badging.Replace($replacement[0], $replacement[1]) $expected } 'metadata mismatch'
        }
    }
    Check 'Debug masquerading as Release rejected' {
        $release = $expected.Clone(); $release.buildType = 'release'
        MustFail { Assert-ApkBadging $badging $release } 'debuggable flag'
    }
    Check 'Non-debuggable APK rejected for Debug' {
        MustFail { Assert-ApkBadging ($badging -replace 'application-debuggable','') $expected } 'debuggable flag'
    }
    $digest = 'a' * 64
    Check 'Single certificate parsed' { Equal (ConvertFrom-ApkCertificateOutput "Signer #1 certificate SHA-256 digest: $digest") $digest }
    Check 'Unsigned output rejected' { MustFail { ConvertFrom-ApkCertificateOutput 'DOES NOT VERIFY' } 'exactly one' }
    Check 'Multiple signers rejected' {
        MustFail { ConvertFrom-ApkCertificateOutput "Signer #1 certificate SHA-256 digest: $digest`nSigner #2 certificate SHA-256 digest: $digest" } 'exactly one'
    }
    $source = Join-Path $fixture 'source.apk'
    $target = Join-Path $fixture 'saved.apk'
    [IO.File]::WriteAllBytes($source, [byte[]](1,2,3))
    $result = [pscustomobject]@{sourceApk=$source;sha256=(Get-FileHash $source).Hash;gitHead='first'}
    Check 'Save creates APK and metadata' {
        Save-ApkArtifact $result $target
        Equal (Get-FileHash $target).Hash $result.sha256
        Equal (Get-Content "$target.json" -Raw | ConvertFrom-Json).gitHead 'first'
    }
    Check 'Rapid repeated replacements preserve all previous APKs and sidecars' {
        foreach ($i in 4..6) {
            [IO.File]::WriteAllBytes($source, [byte[]](1,2,$i))
            $result.sha256 = (Get-FileHash $source).Hash
            $result.gitHead = "commit-$i"
            Save-ApkArtifact $result $target
        }
        Equal @(Get-ChildItem (Join-Path $fixture archive) -Recurse -Filter *.apk).Count 3
        Equal @(Get-ChildItem (Join-Path $fixture archive) -Recurse -Filter *.json).Count 3
        Equal @(Get-ChildItem (Join-Path $fixture archive) -Recurse -Filter *.sha256).Count 3
        foreach ($apk in Get-ChildItem (Join-Path $fixture archive) -Recurse -Filter *.apk) {
            Equal (Get-FileHash $apk.FullName).Hash (Get-Content "$($apk.FullName).json" -Raw | ConvertFrom-Json).sha256
        }
    }
    Check 'Corrupted copy is rejected' {
        $result.sha256 = 'wrong'
        MustFail { Save-ApkArtifact $result $target } 'hash mismatch'
        Equal (Get-FileHash $target).Hash (Get-Content "$target.json" -Raw | ConvertFrom-Json).sha256
    }
    Check 'Missing APK rejected before SDK calls' { MustFail { Test-ApkArtifact "$fixture\absent.apk" '' '' $expected } 'missing or empty' }
    Check 'Empty APK rejected before SDK calls' {
        [IO.File]::WriteAllBytes("$fixture\empty.apk", [byte[]]@())
        MustFail { Test-ApkArtifact "$fixture\empty.apk" '' '' $expected } 'missing or empty'
    }
    Push-Location $fixture
    try {
        '@exit /b 29' | Set-Content gradlew.bat -Encoding ASCII
        Check 'Gradle nonzero exit is propagated' { MustFail { Invoke-ApkGradle @('assembleDebug') } 'Gradle failed \(exit 29\)' }
        & git init --quiet
        & git -c user.name=Test -c user.email=test@example.invalid commit --allow-empty -m fixture --quiet
        Check 'Git clean snapshot accepted' {
            # Ignore the synthetic APK fixtures, so only source changes count.
            '*' | Set-Content .git/info/exclude
            $script:snapshot = Get-ApkSourceState
            Equal $script:snapshot.Dirty $false
            Assert-ApkSourceState $script:snapshot (Get-ApkSourceState)
        }
        Check 'Tracked edits after snapshot rejected' {
            'first' | Set-Content source.txt
            & git add -f source.txt
            $initial = Get-ApkSourceState
            'second' | Set-Content source.txt
            MustFail { Assert-ApkSourceState $initial (Get-ApkSourceState) } 'Source state changed'
        }
        Check 'Dirty edits with unchanged git status rejected by content hash' {
            $initial = Get-ApkSourceState
            'third' | Set-Content source.txt
            $changed = Get-ApkSourceState
            Equal $initial.Status $changed.Status
            MustFail { Assert-ApkSourceState $initial $changed } 'Source state changed.*Files'
        }
        Check 'Git failure cannot masquerade as clean tree' {
            $outside = Join-Path $fixture outside
            New-Item -ItemType Directory $outside | Out-Null
            # Use an explicit invalid GIT_DIR; subdirectories otherwise discover the parent repo.
            $previousGitDir = $env:GIT_DIR
            try { $env:GIT_DIR = "$outside\nonexistent"; MustFail { Get-ApkSourceState } 'Git failed|not a git repository' }
            finally { $env:GIT_DIR = $previousGitDir }
        }
    } finally { Pop-Location }
    Write-Host "All $script:passed regression tests passed."
} finally {
    # Only remove the unique, resolved fixture directory created above.
    $resolved = [IO.Path]::GetFullPath($fixture)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved).StartsWith('dicom-apk-tests-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
exit 0
