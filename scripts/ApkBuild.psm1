Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-ApkGit {
    param([string[]]$Arguments)
    $output = & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $($Arguments -join ' ')" }
    return ($output -join "`n")
}

function Get-ApkSourceState {
    $status = Invoke-ApkGit @('status', '--porcelain', '--untracked-files=all')
    $files = Invoke-ApkGit @('-c', 'core.quotepath=false', 'ls-files', '--cached', '--others', '--exclude-standard')
    $hashes = foreach ($path in ($files -split "`n" | Where-Object { $_ } | Sort-Object -Unique)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            "$path $((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)"
        } else { "$path MISSING" }
    }
    [pscustomobject]@{
        Head = Invoke-ApkGit @('rev-parse', 'HEAD')
        ShortHead = Invoke-ApkGit @('rev-parse', '--short', 'HEAD')
        Branch = Invoke-ApkGit @('rev-parse', '--abbrev-ref', 'HEAD')
        Dirty = [bool]$status; Status = $status; Files = $hashes -join "`n"
    }
}

function Assert-ApkSourceState {
    param($Before, $After)
    foreach ($field in @('Head', 'Branch', 'Status', 'Files')) {
        if ($Before.$field -cne $After.$field) { throw "Source state changed during build ($field). Artifacts are not accepted." }
    }
}

function Invoke-ApkGradle {
    param([string[]]$Tasks)
    # Windows PowerShell 5.1 can turn native stderr into a terminating error when redirected.
    # Decide success from the exit code, retaining Gradle's diagnostic output.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat @Tasks --console=plain --no-configuration-cache
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE): $($Tasks -join ', ')" }
}

function Assert-ApkMetadata {
    param($Actual, [hashtable]$Expected)
    foreach ($key in $Expected.Keys) {
        $property = $Actual.PSObject.Properties[$key]
        if ($null -eq $property -or [string]$property.Value -cne [string]$Expected[$key]) {
            throw "APK metadata mismatch: $key"
        }
    }
}

function ConvertFrom-ApkCertificateOutput {
    param([string]$Output)
    $digests = [regex]::Matches($Output, '(?m)^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$')
    if ($digests.Count -ne 1) { throw 'Expected exactly one verified APK signer.' }
    $digests[0].Groups[1].Value.ToLowerInvariant()
}

function Get-ApkCertificate {
    param([string]$Apksigner, [string]$Apk)
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK missing: $Apk" }
    $ErrorActionPreference = 'Continue'
    $output = & $Apksigner verify --verbose --print-certs $Apk 2>&1
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $Apk" }
    ConvertFrom-ApkCertificateOutput ($output -join "`n")
}

function Assert-ApkBadging {
    param([string]$Badging, [hashtable]$Expected)
    $package = [regex]::Match($Badging, "(?m)^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
    if (-not $package.Success -or $package.Groups[1].Value -cne $Expected.applicationId -or
        $package.Groups[2].Value -cne [string]$Expected.versionCode -or
        $package.Groups[3].Value -cne $Expected.versionName) { throw 'APK package/version metadata mismatch.' }
    $debuggable = $Badging -match '(?m)^application-debuggable\s*$'
    if ($debuggable -ne ($Expected.buildType -eq 'debug')) { throw 'APK debuggable flag does not match build type.' }
}

function Test-ApkArtifact {
    param([string]$Apk, [string]$Aapt, [string]$Apksigner, [hashtable]$Expected)
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf) -or (Get-Item -LiteralPath $Apk).Length -eq 0) {
        throw "APK missing or empty: $Apk"
    }
    $ErrorActionPreference = 'Continue'
    $badging = & $Aapt dump badging $Apk 2>&1
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "aapt failed: $Apk" }
    Assert-ApkBadging ($badging -join "`n") $Expected
    $certificate = Get-ApkCertificate $Apksigner $Apk
    if ($certificate -ne $Expected.certificateSha256) { throw 'APK certificate mismatch (unexpected signing key).' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        $entry = $zip.GetEntry('assets/dicom-kit-build.json')
        if (-not $entry) { throw 'APK embedded Git metadata missing.' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
    Assert-ApkMetadata $metadata $Expected
    $result = [ordered]@{}
    foreach ($key in $Expected.Keys) { $result[$key] = $Expected[$key] }
    $result['sourceApk'] = (Get-Item -LiteralPath $Apk).FullName
    $result['sizeBytes'] = (Get-Item -LiteralPath $Apk).Length
    $result['sha256'] = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
    $result['signatureVerified'] = $true
    $result['buildTime'] = $metadata.buildTime
    $result['validatedAtUtc'] = [DateTime]::UtcNow.ToString('o')
    [pscustomobject]$result
}

function Save-ApkArtifact {
    param($Result, [string]$Target)
    $directory = Split-Path -Parent $Target
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    if (Test-Path -LiteralPath $Target) {
        $hash = (Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash
        $archive = Join-Path $directory ('archive\' + [IO.Path]::GetFileNameWithoutExtension($Target) + '-' +
            [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff') + '-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $archive | Out-Null
        $oldTarget = Join-Path $archive ([IO.Path]::GetFileName($Target))
        Copy-Item -LiteralPath $Target -Destination $oldTarget
        if ((Get-FileHash -LiteralPath $oldTarget -Algorithm SHA256).Hash -ne $hash) { throw 'Archive copy hash mismatch.' }
        foreach ($suffix in @('.json', '.sha256')) {
            if (Test-Path -LiteralPath "$Target$suffix") { Copy-Item -LiteralPath "$Target$suffix" -Destination "$oldTarget$suffix" }
        }
        Write-Host "Previous artifact preserved: $oldTarget (SHA256 $hash)"
    }
    $temporary = "$Target.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        Copy-Item -LiteralPath $Result.sourceApk -Destination $temporary
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $Result.sha256) { throw 'Published APK hash mismatch.' }
        Move-Item -LiteralPath $temporary -Destination $Target -Force
        $Result | Add-Member -NotePropertyName savedApk -NotePropertyValue $Target -Force
        $Result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$Target.json" -Encoding UTF8
        "$($Result.sha256)  $([IO.Path]::GetFileName($Target))" | Set-Content -LiteralPath "$Target.sha256" -Encoding ASCII
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}

Export-ModuleMember -Function *-Apk*
