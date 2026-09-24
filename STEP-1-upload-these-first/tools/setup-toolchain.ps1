# Downloads a portable JDK 17 and the Android command-line tools into .toolchain/
# inside this project.
#
# Nothing is installed system-wide: no PATH changes, no registry entries, no
# administrator rights. Deleting the .toolchain folder reverts everything.
#
# Usage:   .\tools\setup-toolchain.ps1
# Then:    .\build.ps1

$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = Split-Path -Parent $PSScriptRoot
$tc   = Join-Path $root '.toolchain'
$dl   = Join-Path $tc 'downloads'
New-Item -ItemType Directory -Force -Path $dl | Out-Null

function Expand-Into($zip, $target) {
    $tmp = "$target-extract"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
    $inner = (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    # .NET Directory.Move is a same-volume rename: instant, and immune to the
    # MAX_PATH limit that breaks a recursive Move-Item over the SDK's deeply
    # nested tree.
    [System.IO.Directory]::Move($inner, $target)
    Remove-Item -Recurse -Force $tmp
}

# ---- JDK 17 (Eclipse Temurin) ----
$jdk = Join-Path $tc 'jdk-17'
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host 'Downloading JDK 17...'
    $api = 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $asset = (Invoke-RestMethod -Uri $api -TimeoutSec 60)[0]
    $zip = Join-Path $dl 'jdk17.zip'
    Invoke-WebRequest -Uri $asset.binary.package.link -OutFile $zip -UseBasicParsing -TimeoutSec 1800

    $actual = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $asset.binary.package.checksum.ToLower()) { throw 'JDK checksum mismatch' }
    Write-Host "  checksum OK ($($asset.release_name))"

    Expand-Into $zip $jdk
}
Write-Host "JDK ready: $jdk"

# ---- Android command-line tools ----
$sdk = Join-Path $tc 'android-sdk'
$cmdline = Join-Path $sdk 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $cmdline 'bin\sdkmanager.bat'))) {
    Write-Host 'Downloading Android command-line tools...'
    $url = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
    $zip = Join-Path $dl 'cmdline-tools.zip'
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing -TimeoutSec 1800

    $tmp = Join-Path $tc 'clt-extract'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
    [System.IO.Directory]::Move((Join-Path $tmp 'cmdline-tools'), $cmdline)
    Remove-Item -Recurse -Force $tmp
}

# ---- Accept licences ----
# This is what `sdkmanager --licenses` writes, and it works without an
# interactive stdin.
$lic = Join-Path $sdk 'licenses'
New-Item -ItemType Directory -Force -Path $lic | Out-Null
Set-Content -Path (Join-Path $lic 'android-sdk-license') -Encoding ascii -NoNewline `
    -Value "`n24333f8a63b6825ea9c5514f83c2829b004d1fee`n8933bad161af4178b1185d1a37fbf41ea5269c55`nd56f5187479451eabf01fb78af6dfcb131a6481e"
Set-Content -Path (Join-Path $lic 'android-sdk-preview-license') -Encoding ascii -NoNewline `
    -Value "`n84831b9409646a918e30573bab4c9c91346d8abd"

# ---- SDK packages ----
$env:JAVA_HOME = $jdk
$env:ANDROID_USER_HOME = Join-Path $tc '.android'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

Write-Host 'Installing platform-tools, android-36 and build-tools 36.0.0...'
# NOTE: no `2>&1`. In Windows PowerShell 5.1, redirecting a native executable's
# stderr wraps each line in an ErrorRecord and sets $? false even on success.
& (Join-Path $cmdline 'bin\sdkmanager.bat') --sdk_root="$sdk" `
    'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' |
    Out-File -FilePath (Join-Path $tc 'sdk-install.log') -Encoding utf8

if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed (exit $LASTEXITCODE); see $tc\sdk-install.log" }

# ---- local.properties ----
$localProps = Join-Path $root 'local.properties'
$escaped = $sdk -replace '\\', '\\\\' -replace ':', '\:'
Set-Content -Path $localProps -Value "sdk.dir=$escaped" -Encoding ascii

Write-Host ''
Write-Host 'Toolchain ready. Build with:  .\build.ps1' -ForegroundColor Green
