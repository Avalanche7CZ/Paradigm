@echo off
setlocal enabledelayedexpansion

echo ========================================
echo Building All Forge Versions Sequentially and collecting JARs
echo ========================================

:: Where collected jars will be placed (ASCII-only path recommended)
set "ARTIFACT_DIR=%~dp0artifacts\jars"
if not exist "%ARTIFACT_DIR%" (
    mkdir "%ARTIFACT_DIR%"
)
echo Artifacts will be copied to: %ARTIFACT_DIR%

:: Helper function to copy module jars to artifacts folder (skips -sources jars)
:: Usage: call :COPY_JARS module-path
:COPY_JARS
set "MODULE_DIR=%~1"
if not exist "%MODULE_DIR%\build\libs" (
    echo No build/libs for %MODULE_DIR%, skipping copy.
    goto :EOF
)
for %%F in ("%MODULE_DIR%\build\libs\*.jar") do (
    rem skip sources jar if present
    echo %%~nxF | findstr /i "-sources.jar" >nul
    if errorlevel 1 (
        echo Copying %%~nxF to %ARTIFACT_DIR%
        copy /Y "%%~fF" "%ARTIFACT_DIR%\%%~nxF" >nul
    ) else (
        echo Skipping sources: %%~nxF
    )
)
goto :EOF

:: Build steps (stop if any fails)
echo [1/4] Building forge-1.21.1...
call gradlew.bat :forge-1.21.1:clean :forge-1.21.1:build --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: forge-1.21.1
    exit /b 1
)

call :COPY_JARS "forge-1.21.1"
echo SUCCESS: forge-1.21.1
echo.

echo [2/4] Building forge-1.20.1...
call gradlew.bat :forge-1.20.1:clean :forge-1.20.1:build --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: forge-1.20.1
    exit /b 1
)
call :COPY_JARS "forge-1.20.1"
echo SUCCESS: forge-1.20.1
echo.

echo [3/4] Building forge-1.19.2...
call gradlew.bat :forge-1.19.2:clean :forge-1.19.2:build --no-daemon --refresh-dependencies
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: forge-1.19.2
    exit /b 1
)
call :COPY_JARS "forge-1.19.2"
echo SUCCESS: forge-1.19.2
echo.

echo [4/4] Building forge-1.18.2...
call gradlew.bat :forge-1.18.2:clean :forge-1.18.2:build --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: forge-1.18.2
    exit /b 1
)
call :COPY_JARS "forge-1.18.2"
echo SUCCESS: forge-1.18.2
echo.

echo ========================================
echo All versions built successfully!
echo ========================================
echo.
echo Collected JARs:
for %%J in ("%ARTIFACT_DIR%\*.jar") do echo %%~nxJ
echo.
pause
exit /b 0
