# Путь к SDK берём из окружения текущего пользователя, а не из чужого профиля:
# зашитый абсолютный путь работал ровно на одной машине и выдавал её владельца.
param(
    [string]$SdkDir = $(
        if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
        else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    )
)

$sdkDir = $SdkDir
New-Item -ItemType Directory -Force -Path "$sdkDir\cmdline-tools"
Write-Host "Downloading cmdline-tools..."
Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile "cmdline-tools.zip"
Write-Host "Extracting cmdline-tools..."
Expand-Archive -Path "cmdline-tools.zip" -DestinationPath "$sdkDir\cmdline-tools" -Force
Rename-Item -Path "$sdkDir\cmdline-tools\cmdline-tools" -NewName "latest"
Remove-Item -Path "cmdline-tools.zip"

$env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
$sdkmanager = "$sdkDir\cmdline-tools\latest\bin\sdkmanager.bat"

Write-Host "Updating sdkmanager..."
& $sdkmanager --update

Write-Host "Accepting licenses..."
# This accepts all licenses by piping 'y' multiple times
"y`ny`ny`ny`ny`ny`ny`ny" | & $sdkmanager --licenses

Write-Host "Installing platform-tools, platforms;android-34, and build-tools;34.0.0..."
& $sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

Write-Host "Setting environment variables..."
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notmatch "platform-tools") {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$sdkDir\platform-tools", "User")
}
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkDir, "User")

Write-Host "Android SDK setup complete."
