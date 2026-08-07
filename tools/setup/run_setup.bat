@echo off
:: Check for Administrator privileges
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [ERROR] Please run this file as Administrator!
    echo Right-click on run_setup.bat and select "Run as administrator".
    echo.
    pause
    exit /b 1
)

echo Starting Android Environment Setup...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\setup_android_tools.ps1"
pause
