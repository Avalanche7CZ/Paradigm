@echo off
echo ========================================
echo Building All Forge Versions Sequentially
echo ========================================
echo.

echo [1/4] Building forge-1.21.1...
call gradlew.bat :forge-1.21.1:clean :forge-1.21.1:build --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: forge-1.21.1
    pause
    exit /b 1
)
echo SUCCESS: forge-1.21.1
echo.

echo [2/4] Building forge-1.20.1...
call gradlew.bat :forge-1.20.1:clean :forge-1.20.1:build --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: forge-1.20.1
    pause
    exit /b 1
)
echo SUCCESS: forge-1.20.1
echo.

echo [3/4] Building forge-1.19.2...
call gradlew.bat :forge-1.19.2:clean :forge-1.19.2:build --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: forge-1.19.2
    pause
    exit /b 1
)
echo SUCCESS: forge-1.19.2
echo.

echo [4/4] Building forge-1.18.2...
call gradlew.bat :forge-1.18.2:clean :forge-1.18.2:build --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo FAILED: forge-1.18.2
    pause
    exit /b 1
)
echo SUCCESS: forge-1.18.2
echo.

echo ========================================
echo All versions built successfully!
echo ========================================
echo.
echo Output JARs:
echo   forge-1.18.2\build\libs\paradigm-1.18.2-2.0.0.jar
echo   forge-1.19.2\build\libs\paradigm-1.19.2-2.0.0.jar
echo   forge-1.20.1\build\libs\paradigm-1.20.1-2.0.0.jar
echo   forge-1.21.1\build\libs\paradigm-1.21.1-2.0.0.jar
echo.
pause

