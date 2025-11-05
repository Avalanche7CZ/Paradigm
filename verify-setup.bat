@echo off
setlocal enabledelayedexpansion

cd /d "C:\Users\Šimon\Documents\GitHub\Paradigm"

echo ================================================
echo Paradigm Build System Verification
echo ================================================
echo.

echo Checking Java installation...
java -version 2>&1 | findstr /r "version" && echo OK: Java found || echo ERROR: Java not found
echo.

echo Checking Gradle wrapper...
if exist "gradlew.bat" (
    echo OK: gradlew.bat found
) else (
    echo ERROR: gradlew.bat not found
)
echo.

echo Checking build configurations...
for %%f in (gradle\versions\forge-*.gradle.properties) do (
    echo OK: %%~nf found
)
echo.

echo Checking forge source code...
for /d %%d in (forge\src\main\java\eu\avalanche7\paradigm\*) do (
    echo OK: %%~nd found
)
echo.

echo Checking build scripts...
for %%f in (build-forge-*.bat) do (
    echo OK: %%~nf found
)
echo.

echo ================================================
echo Verification Complete!
echo ================================================
echo.
echo To build:
echo   1. Run: build-all-clean.bat
echo   2. Or: gradlew.bat -Dparadigm.version=forge_1_21_1 build
echo.
echo Output will be in: forge\build\libs\
echo.
pause

