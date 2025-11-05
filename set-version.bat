@echo off
setlocal

echo ========================================
echo Paradigm Mod Version Manager
echo ========================================
echo.
echo Current version in build.gradle:
findstr "modVersion" build.gradle
echo.

if "%1"=="" (
    echo Usage: set-version.bat [VERSION]
    echo Example: set-version.bat 2.1.0
    echo.
    echo This will update the mod version for ALL Forge versions.
    pause
    exit /b 1
)

set NEW_VERSION=%1

echo.
echo Updating mod version to: %NEW_VERSION%
echo This will affect ALL Forge versions (1.18.2, 1.19.2, 1.20.1, 1.21.1)
echo.
set /p CONFIRM="Continue? (Y/N): "

if /i not "%CONFIRM%"=="Y" (
    echo Cancelled.
    exit /b 0
)

echo.
echo Updating build.gradle...

powershell -Command "(Get-Content build.gradle) -replace \"modVersion = '[^']*'\", \"modVersion = '%NEW_VERSION%'\" | Set-Content build.gradle"

echo.
echo ========================================
echo Version updated successfully!
echo ========================================
echo.
echo All Forge versions will now build with version: %NEW_VERSION%
echo.
echo Output JARs will be:
echo   paradigm-1.18.2-%NEW_VERSION%.jar
echo   paradigm-1.19.2-%NEW_VERSION%.jar
echo   paradigm-1.20.1-%NEW_VERSION%.jar
echo   paradigm-1.21.1-%NEW_VERSION%.jar
echo.
echo Run 'build-all-sequential.bat' to build all versions with the new version.
echo.
pause

