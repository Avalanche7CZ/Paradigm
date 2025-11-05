@echo off
echo Copying common code from forge-1.21.1 to common module...

xcopy "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\configs" "common\src\main\java\eu\avalanche7\paradigm\configs" /E /I /Y >nul
xcopy "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\core" "common\src\main\java\eu\avalanche7\paradigm\core" /E /I /Y >nul
xcopy "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\data" "common\src\main\java\eu\avalanche7\paradigm\data" /E /I /Y >nul
xcopy "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\modules" "common\src\main\java\eu\avalanche7\paradigm\modules" /E /I /Y >nul
xcopy "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\utils" "common\src\main\java\eu\avalanche7\paradigm\utils" /E /I /Y >nul
xcopy "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\webeditor" "common\src\main\java\eu\avalanche7\paradigm\webeditor" /E /I /Y >nul
xcopy "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\platform\Interfaces" "common\src\main\java\eu\avalanche7\paradigm\platform\Interfaces" /E /I /Y >nul

echo Common code copied successfully!
echo.
echo Common module includes:
echo  - configs/
echo  - core/
echo  - data/
echo  - modules/
echo  - utils/
echo  - webeditor/
echo  - platform/Interfaces/
echo.
echo Version-specific code remains in forge-X.XX.X/:
echo  - Paradigm.java (main mod class)
echo  - platform/*.java (implementations)
echo  - mixin/ (if present)
echo.
pause

