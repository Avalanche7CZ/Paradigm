@echo off
setlocal enabledelayedexpansion

cd /d "C:\Users\Šimon\Documents\GitHub\Paradigm"

echo ================================================
echo Paradigm Multi-Module Forge Build
echo ================================================
echo.
echo Building all Forge versions:
echo  - Forge 1.18.2
echo  - Forge 1.19.2
echo  - Forge 1.20.1
echo  - Forge 1.21.1
echo.

call gradlew.bat clean build

if errorlevel 1 (
    echo.
    echo ERROR: Build failed
    pause
    exit /b 1
)

echo.
echo ================================================
echo Build completed successfully!
echo ================================================
echo.
echo Output JARs in build/libs/:
echo.
for /d %%d in (forge-*) do (
    if exist "%%d\build\libs\*.jar" (
        for %%f in (%%d\build\libs\*.jar) do echo  - %%~nf
    )
)
echo.
pause

