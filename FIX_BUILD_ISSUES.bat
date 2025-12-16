@echo off
REM ============================================================================
REM PARADIGM BUILD FIX - Fixes GradleWorkerMain ClassNotFoundException
REM ============================================================================
REM This script fixes compilation issues for older Forge versions (1.18.2, 1.19.2)
REM It can be run from IDEA's terminal or standalone
REM ============================================================================

setlocal enabledelayedexpansion

cls
echo.
echo ============================================================================
echo PARADIGM BUILD FIXER - Gradle Worker Fix
echo ============================================================================
echo.
echo This fixes the GradleWorkerMain ClassNotFoundException for:
echo  - forge-1.18.2
echo  - forge-1.19.2
echo.
echo Options:
echo   1. Quick Fix (stop daemon + clear workers) - TRY THIS FIRST
echo   2. Full Cleanup (daemon + workers + caches)
echo   3. Stop Gradle Daemon Only
echo   4. Clear Gradle Cache Only
echo   5. Exit
echo.
echo ============================================================================
echo.
set /p choice="Enter choice [1-5]: "

if "%choice%"=="1" goto quickfix
if "%choice%"=="2" goto fullclean
if "%choice%"=="3" goto stopdaemon
if "%choice%"=="4" goto clearcache
if "%choice%"=="5" goto end

echo Invalid choice. Please try again.
goto menu

:menu
echo.
set /p choice="Enter choice [1-5]: "
if "%choice%"=="1" goto quickfix
if "%choice%"=="2" goto fullclean
if "%choice%"=="3" goto stopdaemon
if "%choice%"=="4" goto clearcache
if "%choice%"=="5" goto end
goto menu

:quickfix
cls
echo.
echo ============================================================================
echo QUICK FIX - Starting...
echo ============================================================================
echo.

echo [1/4] Stopping Gradle daemon...
call gradlew --stop >nul 2>&1
if errorlevel 1 echo Warning: Could not stop daemon with gradlew
timeout /t 2 /nobreak >nul

echo [2/4] Clearing worker files from %USERPROFILE%\.gradle\workers...
if exist "%USERPROFILE%\.gradle\workers" (
    rmdir /s /q "%USERPROFILE%\.gradle\workers" 2>nul
    echo ✓ Cleared workers directory
) else (
    echo - Workers directory not found (OK)
)

echo [3/4] Clearing daemon files from %USERPROFILE%\.gradle\daemon...
if exist "%USERPROFILE%\.gradle\daemon" (
    rmdir /s /q "%USERPROFILE%\.gradle\daemon" 2>nul
    echo ✓ Cleared daemon directory
) else (
    echo - Daemon directory not found (OK)
)

echo [4/4] Cleaning build directories...
for /d %%i in (forge-1.18.2 forge-1.19.2) do (
    if exist "%%i\build" (
        rmdir /s /q "%%i\build" 2>nul
        echo ✓ Cleared %%i\build
    )
)

echo.
echo ============================================================================
echo QUICK FIX COMPLETE!
echo ============================================================================
echo.
echo NEXT STEPS:
echo 1. Close IntelliJ IDEA completely
echo 2. Wait 10 seconds
echo 3. Reopen IntelliJ IDEA
echo 4. Let it re-sync (File > Sync with Gradle)
echo 5. Build forge-1.18.2 or forge-1.19.2
echo.
pause
goto end

:fullclean
cls
echo.
echo ============================================================================
echo FULL CLEANUP - Starting...
echo ============================================================================
echo.

echo [1/6] Stopping Gradle daemon...
call gradlew --stop >nul 2>&1
timeout /t 2 /nobreak >nul
echo ✓ Daemon stopped

echo [2/6] Clearing daemon files...
if exist "%USERPROFILE%\.gradle\daemon" (
    rmdir /s /q "%USERPROFILE%\.gradle\daemon" 2>nul
    echo ✓ Cleared daemon
)

echo [3/6] Clearing worker files...
if exist "%USERPROFILE%\.gradle\workers" (
    rmdir /s /q "%USERPROFILE%\.gradle\workers" 2>nul
    echo ✓ Cleared workers
)

echo [4/6] Clearing wrapper cache...
if exist "%USERPROFILE%\.gradle\wrapper" (
    rmdir /s /q "%USERPROFILE%\.gradle\wrapper" 2>nul
    echo ✓ Cleared wrapper
)

echo [5/6] Clearing build caches...
for /d %%i in (forge-1.18.2 forge-1.19.2 forge-1.20.1 forge-1.21.1) do (
    if exist "%%i\.gradle" (
        rmdir /s /q "%%i\.gradle" 2>nul
    )
    if exist "%%i\build" (
        rmdir /s /q "%%i\build" 2>nul
    )
)
echo ✓ Cleared build caches

echo [6/6] Cleaning .gradle folder in workspace...
if exist ".gradle" (
    rmdir /s /q ".gradle" 2>nul
    echo ✓ Cleared workspace .gradle
)

echo.
echo ============================================================================
echo FULL CLEANUP COMPLETE!
echo ============================================================================
echo.
echo IMPORTANT:
echo 1. Close IntelliJ IDEA COMPLETELY
echo 2. Go to Help > Invalidate Caches and restart IntelliJ IDEA
echo 3. Wait for gradle sync to complete
echo 4. Try building again
echo.
pause
goto end

:stopdaemon
cls
echo.
echo Stopping Gradle daemon...
call gradlew --stop
echo Done!
echo.
pause
goto end

:clearcache
cls
echo.
echo Clearing Gradle cache...
if exist "%USERPROFILE%\.gradle\daemon" rmdir /s /q "%USERPROFILE%\.gradle\daemon" 2>nul
if exist "%USERPROFILE%\.gradle\workers" rmdir /s /q "%USERPROFILE%\.gradle\workers" 2>nul
echo Done!
echo.
pause
goto end

:end
endlocal
exit /b 0

