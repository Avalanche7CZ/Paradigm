@echo off
REM ====================================================================
REM Paradigm Minecraft Server Startup Script (Windows)
REM ====================================================================
REM This script ensures your server restarts automatically after shutdown
REM Required for Paradigm's Restart module to work properly
REM ====================================================================

title Minecraft Server - Paradigm

REM ====================================================================
REM Configuration - Adjust these settings for your server
REM ====================================================================

REM Memory allocation (in MB)
set MIN_RAM=2048
set MAX_RAM=4096

REM Server jar file name
set SERVER_JAR=forge-server.jar

REM Java arguments (advanced users only)
set JAVA_ARGS=-Xms%MIN_RAM%M -Xmx%MAX_RAM%M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1

REM Server startup mode (nogui for headless, remove for GUI)
set SERVER_MODE=nogui

REM Restart delay (in seconds)
set RESTART_DELAY=10

REM ====================================================================
REM Script Start - Do not modify below this line unless you know what you're doing
REM ====================================================================

echo ====================================================================
echo   Paradigm Minecraft Server Launcher
echo ====================================================================
echo   Server JAR: %SERVER_JAR%
echo   RAM: %MIN_RAM%MB - %MAX_RAM%MB
echo   Auto-restart: ENABLED
echo ====================================================================
echo.

REM Check if Java is installed
java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Java is not installed or not in PATH!
    echo Please install Java and try again.
    echo.
    pause
    exit /b 1
)

REM Check if server jar exists
if not exist "%SERVER_JAR%" (
    echo [ERROR] Server jar file not found: %SERVER_JAR%
    echo Please make sure the server jar file exists in the current directory.
    echo.
    pause
    exit /b 1
)

REM Main restart loop
:restart_loop
echo.
echo ====================================================================
echo   Starting Minecraft Server...
echo   Time: %date% %time%
echo ====================================================================
echo.

REM Start the server
java %JAVA_ARGS% -jar "%SERVER_JAR%" %SERVER_MODE%

REM Capture exit code
set EXIT_CODE=%ERRORLEVEL%

echo.
echo ====================================================================
echo   Server stopped with exit code: %EXIT_CODE%
echo   Time: %date% %time%
echo ====================================================================
echo.

REM Check if server stopped due to error
if %EXIT_CODE% NEQ 0 (
    echo [WARNING] Server stopped with non-zero exit code!
    echo This might indicate a crash or error.
    echo.
)

echo Restarting in %RESTART_DELAY% seconds...
echo Press Ctrl+C to cancel restart.
timeout /t %RESTART_DELAY% /nobreak

REM Loop back to restart
goto restart_loop

