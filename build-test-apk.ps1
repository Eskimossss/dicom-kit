<#
.SYNOPSIS
    Автоматизированная сборка тестового отладочного APK DICOM Kit.

.DESCRIPTION
    Скрипт проверяет состояние репозитория, выполняет assembleDebug через Gradle Wrapper,
    копирует собранный APK в каталог builds/ с уникальным именем DICOM-Kit-<versionName>-debug.apk,
    защищает от незаметной перезаписи предыдущих сборок и выводит подробную информацию
    (путь, версию, коммит, SHA-256).

.PARAMETER AllowDirty
    Разрешить сборку из рабочего дерева с незакоммиченными изменениями.

.PARAMETER Force
    Перезаписать существующий файл с отличающимся хешем без создания архивной копии.

.EXAMPLE
    .\build-test-apk.ps1
    .\build-test-apk.ps1 -AllowDirty
#>

[CmdletBinding()]
param(
    [switch]$AllowDirty,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$RepoRoot = $PSScriptRoot
if (-not $RepoRoot) {
    $RepoRoot = (Get-Location).Path
}

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " DICOM Kit -- Сборка тестового APK (assembleDebug)" -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# 1. Проверка наличия Gradle Wrapper
$Gradlew = Join-Path $RepoRoot "gradlew.bat"
if (-not (Test-Path -Path $Gradlew)) {
    Write-Error "Gradle Wrapper (gradlew.bat) не найден в корне репозитория: $RepoRoot"
    exit 1
}

# 1.1 Настройка JAVA_HOME
if (-not $env:JAVA_HOME) {
    $DefaultJdk = "C:\Program Files\Android\openjdk\jdk-21.0.8"
    if (Test-Path $DefaultJdk) {
        $env:JAVA_HOME = $DefaultJdk
        Write-Host "Установлен JAVA_HOME: $DefaultJdk" -ForegroundColor DarkGray
    }
}

# 2. Определение текущей версии из app/build.gradle.kts
$BuildGradle = Join-Path $RepoRoot "app\build.gradle.kts"
if (-not (Test-Path $BuildGradle)) {
    Write-Error "Файл конфигурации app/build.gradle.kts не найден: $BuildGradle"
    exit 1
}

$BuildGradleLines = Get-Content -Path $BuildGradle
$VersionName = $null
$VersionCode = $null

foreach ($line in $BuildGradleLines) {
    if ($line -match 'versionName\s*=\s*"([^"]+)"') {
        $VersionName = $matches[1]
    }
    if ($line -match 'versionCode\s*=\s*(\d+)') {
        $VersionCode = $matches[1]
    }
}

if (-not $VersionName) {
    Write-Error "Не удалось определить versionName из app/build.gradle.kts"
    exit 1
}
if (-not $VersionCode) {
    Write-Error "Не удалось определить versionCode из app/build.gradle.kts"
    exit 1
}

# 2.1 Определение Git-метаданных
$gitBranch = "unknown"
$gitCommit = "unknown"
$isDirty = $false

try {
    $branchOut = & git rev-parse --abbrev-ref HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and $branchOut) {
        $gitBranch = $branchOut.Trim()
    }

    $commitOut = & git rev-parse --short HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and $commitOut) {
        $gitCommit = $commitOut.Trim()
    }

    $statusOut = & git status --porcelain 2>$null
    if ($LASTEXITCODE -eq 0 -and $statusOut -and $statusOut.Trim().Length -gt 0) {
        $isDirty = $true
    }
} catch {
    Write-Warning "Не удалось выполнить Git-команды для получения метаданных: $_"
}

Write-Host "Версия:       $VersionName (code: $VersionCode)" -ForegroundColor White
Write-Host "Git-ветка:    $gitBranch" -ForegroundColor White
Write-Host "Git-коммит:   $gitCommit" -ForegroundColor White

# 3. Проверка чистоты рабочего дерева
if ($isDirty) {
    if (-not $AllowDirty) {
        Write-Host "ОШИБКА: Рабочее дерево содержит незакоммиченные изменения." -ForegroundColor Red
        Write-Host "Для создания официальных проверяемых сборок требуется чистое состояние рабочей ветки." -ForegroundColor Red
        Write-Host "Закоммитьте изменения либо запустите скрипт с параметром: .\build-test-apk.ps1 -AllowDirty" -ForegroundColor Yellow
        exit 1
    } else {
        Write-Warning "ВНИМАНИЕ: Сборка выполняется из незакоммиченного рабочего дерева (-AllowDirty). Метаданные будут помечены как dirty."
        $gitCommit = "$gitCommit-dirty"
    }
} else {
    Write-Host "Рабочее дерево: ЧИСТОЕ (соответствует HEAD $gitCommit)" -ForegroundColor Green
}

# 4. Выполнение assembleDebug
Write-Host "`nЗапуск задачи assembleDebug через Gradle Wrapper..." -ForegroundColor Cyan
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

& $Gradlew assembleDebug
$GradleExitCode = $LASTEXITCODE
$Stopwatch.Stop()

if ($GradleExitCode -ne 0) {
    Write-Error "Сборка завершилась с ошибкой (код возврата Gradle: $GradleExitCode). Длительность: $($Stopwatch.Elapsed.ToString('mm\:ss'))"
    exit $GradleExitCode
}

Write-Host "Сборка assembleDebug успешно завершена за $($Stopwatch.Elapsed.ToString('mm\:ss'))." -ForegroundColor Green

# 5. Поиск собранного APK
$SourceApk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $SourceApk)) {
    Write-Error "Собранный файл app-debug.apk не найден по ожидаемому пути: $SourceApk"
    exit 1
}

# 6. Каталог назначения и имя файла
$BuildsDir = Join-Path $RepoRoot "builds"
if (-not (Test-Path $BuildsDir)) {
    New-Item -ItemType Directory -Path $BuildsDir -Force | Out-Null
}

$TargetApkName = "DICOM-Kit-$VersionName-debug.apk"
$TargetApkPath = Join-Path $BuildsDir $TargetApkName

# 7. Защита от незаметной перезаписи
$sourceHash = (Get-FileHash -Path $SourceApk -Algorithm SHA256).Hash

if (Test-Path $TargetApkPath) {
    $existingHash = (Get-FileHash -Path $TargetApkPath -Algorithm SHA256).Hash
    if ($sourceHash -eq $existingHash) {
        Write-Host "`nВнимание: Файл с идентичным содержимым (хеш совпадает) уже существует по пути:" -ForegroundColor Yellow
        Write-Host "  $TargetApkPath" -ForegroundColor DarkGray
    } else {
        if (-not $Force) {
            $timestamp = (Get-Item $TargetApkPath).LastWriteTime.ToString("yyyyMMdd-HHmmss")
            $backupName = "DICOM-Kit-$VersionName-$timestamp-debug.apk"
            $backupPath = Join-Path $BuildsDir $backupName
            Move-Item -Path $TargetApkPath -Destination $backupPath -Force
            Write-Warning "Обнаружен существующий APK с отличающимся хешем!`nПредыдущий файл сохранён как: $backupName"
        } else {
            Write-Warning "Существующий файл с отличающимся хешем перезаписан по флагу -Force."
        }
    }
}

# Копирование свежего APK
Copy-Item -Path $SourceApk -Destination $TargetApkPath -Force

$finalApkItem = Get-Item $TargetApkPath
$finalSizeMb = [math]::Round($finalApkItem.Length / 1MB, 2)
$finalHash = (Get-FileHash -Path $TargetApkPath -Algorithm SHA256).Hash

# 8. Итоговый отчёт
Write-Host "`n====================================================" -ForegroundColor Green
Write-Host " ТЕСТОВЫЙ APK УСПЕШНО СОБРАН И СОХРАНЁН" -ForegroundColor Green
Write-Host "====================================================" -ForegroundColor Green
Write-Host "Путь к файлу:   $TargetApkPath" -ForegroundColor White
Write-Host "Имя файла:      $TargetApkName" -ForegroundColor White
Write-Host "Размер:         $($finalApkItem.Length) байт ($finalSizeMb МБ)" -ForegroundColor White
Write-Host "Версия:         $VersionName" -ForegroundColor White
Write-Host "Version Code:   $VersionCode" -ForegroundColor White
Write-Host "Git-ветка:      $gitBranch" -ForegroundColor White
Write-Host "Git-коммит:     $gitCommit" -ForegroundColor White
Write-Host "SHA-256 хеш:    $finalHash" -ForegroundColor Yellow
Write-Host "====================================================`n" -ForegroundColor Green

exit 0