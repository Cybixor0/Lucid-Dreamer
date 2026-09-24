# Convenience wrapper: runs Gradle against the portable toolchain in .toolchain
# without touching JAVA_HOME, PATH or anything else on the machine.
#
#   .\build.ps1                 -> assembleDebug
#   .\build.ps1 test            -> unit tests
#   .\build.ps1 assembleDebug lint
#
# If you have your own JDK 17+ and Android SDK, you can ignore this and use
# .\gradlew directly. See BUILDING.md.

# NOTE: deliberately NOT 'Stop'. Gradle and the Android SDK tools write warnings
# to stderr, and in Windows PowerShell 5.1 a native process's stderr becomes an
# ErrorRecord, which would abort this script on a harmless warning.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$jdk  = Join-Path $root '.toolchain\jdk-17'
$sdk  = Join-Path $root '.toolchain\android-sdk'

if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host "Portable JDK not found at $jdk." -ForegroundColor Red
    Write-Host "Run tools\setup-toolchain.ps1 first, or use .\gradlew with your own JDK 17+."
    exit 1
}

$env:JAVA_HOME        = $jdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = Join-Path $root '.toolchain\.android'

$tasks = if ($args.Count -gt 0) { $args } else { @('assembleDebug') }
& (Join-Path $root 'gradlew.bat') @tasks
exit $LASTEXITCODE
