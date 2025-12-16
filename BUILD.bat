@echo off
REM ============================================================================
REM PARADIGM BUILD SCRIPT - Build any Forge version
REM ============================================================================
REM Usage: BUILD.bat [version] [clean]
REM   version: 1.18.2, 1.19.2, 1.20.1, 1.21.1, or "all"
REM   clean: add "clean" as second parameter to do a clean build
REM ============================================================================

setlocal enabledelayedexpansion

REM Default to 1.20.1 if no version specified
set VERSION=%1
if "%VERSION%"=="" (
    cls
    echo.
    echo ============================================================================
    echo PARADIGM BUILD SCRIPT
    echo ============================================================================
    echo.
    echo Usage: BUILD.bat [version] [clean]
    echo.
    echo Available versions:
    echo   1.18.2
    echo   1.19.2
    echo   1.20.1 (default, fastest)
    echo   1.21.1
    echo   all    (builds all versions sequentially)
    echo.
    echo Examples:
    echo   BUILD.bat 1.20.1
    echo   BUILD.bat 1.18.2 clean
    echo   BUILD.bat all
    echo.
    set /p VERSION="Enter version [1.20.1]: "
    if "!VERSION!"=="" set VERSION=1.20.1
)

set CLEAN=%2
if "%CLEAN%"=="" set CLEAN=

echo.
echo ============================================================================
echo PARADIGM BUILD - Version: %VERSION%
echo ============================================================================
echo.

if "%VERSION%"=="all" goto buildall
if "%VERSION%"=="1.18.2" goto build182
if "%VERSION%"=="1.19.2" goto build192
if "%VERSION%"=="1.20.1" goto build201
if "%VERSION%"=="1.21.1" goto build211

echo Invalid version: %VERSION%
echo Valid versions: 1.18.2, 1.19.2, 1.20.1, 1.21.1, all
pause
goto end

:buildall
call :build182
call :build192
call :build201
call :build211
goto builddone

:build182
call :doBuild 1.18.2 forge-1.18.2
goto builddone

:build192
call :doBuild 1.19.2 forge-1.19.2
goto builddone

:build201
call :doBuild 1.20.1 forge-1.20.1
goto builddone

:build211
call :doBuild 1.21.1 forge-1.21.1
goto builddone

:doBuild
set MCVER=%~1
set FOLDER=%~2

echo.
echo Building %MCVER% (%FOLDER%)...
echo.

if "%CLEAN%"=="clean" (
    echo Running clean build...
    call gradlew.bat :%FOLDER%:clean :%FOLDER%:build
) else (
    call gradlew.bat :%FOLDER%:build
)

if errorlevel 1 (
    echo.
    echo ERROR: Build failed for %MCVER%!
    echo.
    exit /b 1
)
exit /b 0

:builddone
echo.
echo ============================================================================
echo BUILD COMPLETE!
echo ============================================================================
echo.
echo Output JARs are in: artifacts\jars\
echo.
pause
goto end

:end
endlocal
exit /b 0

