<# Run after installing the APKs built with -Pstage51Isolated=true. Emulator display/settings are restored. #>
[CmdletBinding()]
param([string]$Serial = 'emulator-5554', [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [ValidateSet('Alpha3','Alpha4')][string]$Suite = 'Alpha3',
    [ValidateSet('360-portrait','360-landscape','393-portrait','393-landscape','412-portrait','412-landscape','tablet-landscape','tablet-portrait')][string[]]$OnlyConfiguration)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
function Invoke-Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')" }
    return ($output -join "`n")
}
if ($Serial -notlike 'emulator-*' -or (Invoke-Adb @('shell','getprop','ro.kernel.qemu')).Trim() -ne '1') { throw 'This display matrix is restricted to an emulator.' }
if ((Invoke-Adb @('shell','pm','list','instrumentation')) -notmatch 'ru.medeng.dicomtool.storagesafetytest.test') { throw 'Install isolated Debug and androidTest APKs first.' }
$size = Invoke-Adb @('shell','wm','size'); $density = Invoke-Adb @('shell','wm','density')
$oldSize = [regex]::Match($size, 'Override size:\s*(\d+x\d+)').Groups[1].Value
$oldDensity = [regex]::Match($density, 'Override density:\s*(\d+)').Groups[1].Value
$settings = @(@('global','window_animation_scale'),@('global','transition_animation_scale'),@('global','animator_duration_scale'),@('secure','show_ime_with_hard_keyboard'))
$previous = @{}
foreach ($setting in $settings) { $previous[$setting[1]] = (Invoke-Adb @('shell','settings','get',$setting[0],$setting[1])).Trim() }
$evidence = Join-Path $repo ("builds\$($Suite.ToLowerInvariant())-evidence\matrix-" + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
$classes = 'ru.medeng.dicomtool.Alpha3AcceptanceUiTest,ru.medeng.dicomtool.Alpha3NavigationUiTest'
$testCount = 9
if ($Suite -eq 'Alpha4') { $classes += ',ru.medeng.dicomtool.Alpha4UxUiTest,ru.medeng.dicomtool.Alpha4AppAuditUiTest'; $testCount = 19 }
New-Item -ItemType Directory $evidence -Force | Out-Null
$configurations = @(
    @('360-portrait','720x1280','320'), @('360-landscape','1280x720','320'),
    @('393-portrait','786x1400','320'), @('393-landscape','1400x786','320'),
    @('412-portrait','824x1460','320'), @('412-landscape','1460x824','320'),
    @('tablet-landscape','1280x800','160'), @('tablet-portrait','800x1280','160')
)
if ($OnlyConfiguration) { $configurations = @($configurations | Where-Object { $_[0] -in $OnlyConfiguration }) }
$results = @()
try {
    foreach ($setting in $settings) {
        $value = if ($setting[1] -eq 'show_ime_with_hard_keyboard') { '1' } else { '0' }
        Invoke-Adb @('shell','settings','put',$setting[0],$setting[1],$value) | Out-Null
    }
    foreach ($configuration in $configurations) {
        $name = $configuration[0]
        Invoke-Adb @('shell','wm','size',$configuration[1]) | Out-Null
        Invoke-Adb @('shell','wm','density',$configuration[2]) | Out-Null
        Start-Sleep -Seconds 1
        $log = Invoke-Adb @('shell','am','instrument','-w','-r','-e','class',
            $classes,
            'ru.medeng.dicomtool.storagesafetytest.test/androidx.test.runner.AndroidJUnitRunner')
        $log | Set-Content (Join-Path $evidence "$name.log") -Encoding UTF8
        $passed = $log -match "(?m)^OK \($testCount tests\)" -and $log -notmatch 'FAILURES!!!|Process crashed'
        $results += [pscustomobject]@{Configuration=$name;Size=$configuration[1];Density=$configuration[2];Tests=$testCount;Passed=$passed}
        $destination = Join-Path $evidence $name
        New-Item -ItemType Directory $destination -Force | Out-Null
        Invoke-Adb @('pull','/sdcard/Android/data/ru.medeng.dicomtool.storagesafetytest/files/alpha3-ui',$destination) | Out-Null
        if ($Suite -eq 'Alpha4') { Invoke-Adb @('pull','/sdcard/Android/data/ru.medeng.dicomtool.storagesafetytest/files/alpha4-ui',$destination) | Out-Null }
        Write-Host "$name passed: $passed"
        $results | ConvertTo-Json | Set-Content (Join-Path $evidence 'results.json') -Encoding UTF8
    }
} finally {
    Invoke-Adb @('shell','wm','size', $(if ($oldSize) { $oldSize } else { 'reset' })) | Out-Null
    Invoke-Adb @('shell','wm','density', $(if ($oldDensity) { $oldDensity } else { 'reset' })) | Out-Null
    foreach ($setting in $settings) {
        $value = $previous[$setting[1]]
        if ($value -eq 'null') { Invoke-Adb @('shell','settings','delete',$setting[0],$setting[1]) | Out-Null }
        else { Invoke-Adb @('shell','settings','put',$setting[0],$setting[1],$value) | Out-Null }
    }
}
Write-Host "Evidence: $evidence"
if (@($results | Where-Object { -not $_.Passed }).Count -gt 0) { throw 'UI layout matrix failed; inspect logs and screenshots.' }
