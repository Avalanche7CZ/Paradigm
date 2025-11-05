@echo off
setlocal enableextensions enabledelayedexpansion

echo === Safe build for forge-1.19.2 ===

:: Use a safe ASCII-only gradle user home to avoid worker classpath issues
:: Change these if you prefer another path (must be ASCII-only)
set "SAFE_GRADLE_HOME=C:\gradle-cache"
set "SAFE_TEMP=C:\gradle-temp"
set "LOGFILE=%~dp0build-1.19.2-safe.log"

:: Create folders if missing
if not exist "%SAFE_GRADLE_HOME%" (
    echo Creating %SAFE_GRADLE_HOME% ...
    mkdir "%SAFE_GRADLE_HOME%"
)
if not exist "%SAFE_TEMP%" (
    echo Creating %SAFE_TEMP% ...
    mkdir "%SAFE_TEMP%"
)

:: Point Java/Gradle to safe temp locations (ASCII-only)
set "TEMP=%SAFE_TEMP%"
set "TMP=%SAFE_TEMP%"
set "GRADLE_USER_HOME=%SAFE_GRADLE_HOME%"

:: Ensure Java uses the safe tmp dir as well
set "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=%SAFE_TEMP%"

:: Optional: increase Gradle memory if needed
set "GRADLE_OPTS=-Xmx4G"

necho Using GRADLE_USER_HOME=%GRADLE_USER_HOME%"
echo Using TEMP=%TEMP%"
echo Logging Gradle output to %LOGFILE%

:: Build only the 1.19.2 module with stacktrace and refresh-dependencies, log to file and to console
echo Running: gradlew.bat :forge-1.19.2:clean :forge-1.19.2:build --no-daemon --refresh-dependencies --stacktrace
call "%~dp0gradlew.bat" :forge-1.19.2:clean :forge-1.19.2:build --no-daemon --refresh-dependencies --stacktrace 2>&1 | tee "%LOGFILE%"
set EXIT_CODE=%ERRORLEVEL%
echo Gradle finished with exit code %EXIT_CODE%
if %EXIT_CODE% EQU 0 (
    echo SUCCESS: forge-1.19.2 built
) else (
    echo FAILURE: forge-1.19.2 build failed (exit %EXIT_CODE%). See %LOGFILE% for details.
)
pause
exit /b %EXIT_CODE%
