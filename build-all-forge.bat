@echo off
cd /d "C:\Users\Šimon\Documents\GitHub\Paradigm"
for %%v in (1.18.2 1.19.2 1.20.1 1.21.1) do (
    echo Building Forge %%v...
    call gradlew.bat "-Dparadigm.version=forge_%%v" build
    if errorlevel 1 (
        echo Build failed for Forge %%v
        exit /b 1
    )
)
echo All builds completed successfully!
pause

