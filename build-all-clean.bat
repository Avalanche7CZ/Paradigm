@echo off
setlocal enabledelayedexpansion

cd /d "C:\Users\Šimon\Documents\GitHub\Paradigm"

echo ================================================
echo Building Paradigm Forge Mod - All Versions
echo ================================================

for %%v in (1.18.2 1.19.2 1.20.1 1.21.1) do (
    echo.
    echo ================================================
    echo Building Forge %%v...
    echo ================================================
    call gradlew.bat -Dparadigm.version=forge_%%v clean build
    if errorlevel 1 (
        echo ERROR: Build failed for Forge %%v
        pause
        exit /b 1
    )
    echo Build successful for Forge %%v
)

echo.
echo ================================================
echo All builds completed successfully!
echo ================================================
echo.
echo Output JARs:
for %%v in (1.18.2 1.19.2 1.20.1 1.21.1) do (
    if exist "forge\build\libs\paradigm-forge-%%v-2.0.0.jar" (
        echo  - paradigm-forge-%%v-2.0.0.jar
    )
)
echo.
pause

