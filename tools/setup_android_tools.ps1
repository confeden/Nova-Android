# Setup Android Development Tools for NovaAndroid
# This script installs OpenJDK 17, Android SDK Command Line Tools, Platform Tools (ADB), and configures Environment Variables.
# Run this script in an Elevated (Administrator) PowerShell window.

$ErrorActionPreference = "Stop"

Write-Host "=== Starting Android SDK & JDK Setup for NovaAndroid ===" -ForegroundColor Cyan

# 1. Install JDK 17 using Winget
Write-Host "`n[1/5] Installing OpenJDK 17..." -ForegroundColor Yellow
if (Get-Command winget -ErrorAction SilentlyContinue) {
    Write-Host "Using winget to install Temurin JDK 17..."
    winget install --id EclipseAdoptium.Temurin.17 --silent --accept-package-agreements --accept-source-agreements
} else {
    Write-Host "winget not found. Please install JDK 17 manually or install winget." -ForegroundColor Red
    exit
}

# 2. Determine JDK path and set JAVA_HOME
$jdkPath = "C:\Program Files\Eclipse Adoptium\jdk-17.*"
$jdkDir = Resolve-Path $jdkPath | Select-Object -ExpandProperty Path
if ($jdkDir) {
    Write-Host "Found JDK at: $jdkDir" -ForegroundColor Green
    [System.Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkDir, "User")
    $env:JAVA_HOME = $jdkDir
} else {
    Write-Host "Could not locate installed JDK 17 path. Please verify installation." -ForegroundColor Red
}

# 3. Create Android SDK folder structure
Write-Host "`n[2/5] Setting up Android SDK directories..." -ForegroundColor Yellow
$sdkRoot = "$env:USERPROFILE\AppData\Local\Android\Sdk"
$cmdlineToolsDir = "$sdkRoot\cmdline-tools"
if (!(Test-Path $cmdlineToolsDir)) {
    New-Item -ItemType Directory -Force -Path $cmdlineToolsDir | Out-Null
}

[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkRoot, "User")
$env:ANDROID_HOME = $sdkRoot

# 4. Download and extract Command Line Tools
Write-Host "`n[3/5] Downloading Android Command Line Tools..." -ForegroundColor Yellow
$zipUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$zipPath = "$env:TEMP\cmdline-tools.zip"

Write-Host "Downloading: $zipUrl"
Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath

Write-Host "Extracting Command Line Tools..."
$tempExtract = "$env:TEMP\cmdline-tools-extracted"
if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
Expand-Archive -Path $zipPath -DestinationPath $tempExtract

# Move to the correct SDK structure (cmdline-tools/latest/bin/...)
$latestDir = "$cmdlineToolsDir\latest"
if (Test-Path $latestDir) { Remove-Item -Recurse -Force $latestDir }
Move-Item -Path "$tempExtract\cmdline-tools" -Destination $latestDir

# 5. Add Tools to PATH
Write-Host "`n[4/5] Configuring Environment PATH variables..." -ForegroundColor Yellow
$userPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
$pathsToAdd = @(
    "$sdkRoot\cmdline-tools\latest\bin",
    "$sdkRoot\platform-tools"
)

foreach ($path in $pathsToAdd) {
    if ($userPath -notlike "*$path*") {
        $userPath = "$path;$userPath"
        Write-Host "Adding to PATH: $path"
    }
}
[System.Environment]::SetEnvironmentVariable("Path", $userPath, "User")
# Apply to current session path too
foreach ($path in $pathsToAdd) {
    $env:Path = "$path;$env:Path"
}

# 6. Install SDK packages using sdkmanager
Write-Host "`n[5/5] Installing SDK platforms and Platform-tools (ADB)..." -ForegroundColor Yellow
# Accept licenses automatically
$sdkManager = "$latestDir\bin\sdkmanager.bat"
if (Test-Path $sdkManager) {
    Write-Host "Accepting Android SDK Licenses..."
    $yes = @("y") * 30
    $yes | & $sdkManager --licenses | Out-Null
    
    Write-Host "Installing packages (platform-tools, build-tools;34.0.0, platforms;android-34)..."
    & $sdkManager "platform-tools" "build-tools;34.0.0" "platforms;android-34"
} else {
    Write-Host "sdkmanager.bat not found at $sdkManager!" -ForegroundColor Red
}

# 7. Download USB Driver for Pixel
Write-Host "`n=== Downloading Google USB Driver for Pixel ===" -ForegroundColor Cyan
$usbUrl = "https://dl.google.com/android/repository/usb_driver_r13-windows.zip"
$usbZipPath = "$env:TEMP\usb_driver.zip"
$usbDest = "$sdkRoot\extras\google\usb_driver"

if (!(Test-Path $usbDest)) {
    Write-Host "Downloading Google USB Driver ZIP..."
    Invoke-WebRequest -Uri $usbUrl -OutFile $usbZipPath
    Write-Host "Extracting USB Driver to: $usbDest"
    Expand-Archive -Path $usbZipPath -DestinationPath "$sdkRoot\extras"
} else {
    Write-Host "Google USB Driver is already downloaded at $usbDest" -ForegroundColor Green
}

Write-Host "`n=== Setup Completed! ===" -ForegroundColor Green
Write-Host "Please close this terminal and open a new one to refresh Environment Variables." -ForegroundColor Yellow
Write-Host "To verify USB Driver/ADB recognition:" -ForegroundColor Cyan
Write-Host "1. Reconnect your Pixel 4a"
Write-Host "2. Run: adb devices"
