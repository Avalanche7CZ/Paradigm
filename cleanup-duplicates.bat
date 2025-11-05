@echo off
echo ================================================
echo Cleaning up duplicated code from version modules
echo ================================================
echo.
echo The following will be DELETED from each version:
echo  - configs/ (now in common)
echo  - core/ (now in common)
echo  - data/ (now in common)
echo  - modules/ (now in common)
echo  - utils/ (now in common)
echo  - webeditor/ (now in common)
echo  - platform/Interfaces/ (now in common)
echo.
echo The following will be KEPT in each version:
echo  - Paradigm.java (version-specific)
echo  - platform/*.java implementations (version-specific)
echo  - mixin/ (version-specific)
echo.
pause

echo.
echo Cleaning forge-1.18.2...
rd /s /q "forge-1.18.2\src\main\java\eu\avalanche7\paradigm\configs" 2>nul
rd /s /q "forge-1.18.2\src\main\java\eu\avalanche7\paradigm\core" 2>nul
rd /s /q "forge-1.18.2\src\main\java\eu\avalanche7\paradigm\data" 2>nul
rd /s /q "forge-1.18.2\src\main\java\eu\avalanche7\paradigm\modules" 2>nul
rd /s /q "forge-1.18.2\src\main\java\eu\avalanche7\paradigm\utils" 2>nul
rd /s /q "forge-1.18.2\src\main\java\eu\avalanche7\paradigm\webeditor" 2>nul
rd /s /q "forge-1.18.2\src\main\java\eu\avalanche7\paradigm\platform\Interfaces" 2>nul

echo Cleaning forge-1.19.2...
rd /s /q "forge-1.19.2\src\main\java\eu\avalanche7\paradigm\configs" 2>nul
rd /s /q "forge-1.19.2\src\main\java\eu\avalanche7\paradigm\core" 2>nul
rd /s /q "forge-1.19.2\src\main\java\eu\avalanche7\paradigm\data" 2>nul
rd /s /q "forge-1.19.2\src\main\java\eu\avalanche7\paradigm\modules" 2>nul
rd /s /q "forge-1.19.2\src\main\java\eu\avalanche7\paradigm\utils" 2>nul
rd /s /q "forge-1.19.2\src\main\java\eu\avalanche7\paradigm\webeditor" 2>nul
rd /s /q "forge-1.19.2\src\main\java\eu\avalanche7\paradigm\platform\Interfaces" 2>nul

echo Cleaning forge-1.20.1...
rd /s /q "forge-1.20.1\src\main\java\eu\avalanche7\paradigm\configs" 2>nul
rd /s /q "forge-1.20.1\src\main\java\eu\avalanche7\paradigm\core" 2>nul
rd /s /q "forge-1.20.1\src\main\java\eu\avalanche7\paradigm\data" 2>nul
rd /s /q "forge-1.20.1\src\main\java\eu\avalanche7\paradigm\modules" 2>nul
rd /s /q "forge-1.20.1\src\main\java\eu\avalanche7\paradigm\utils" 2>nul
rd /s /q "forge-1.20.1\src\main\java\eu\avalanche7\paradigm\webeditor" 2>nul
rd /s /q "forge-1.20.1\src\main\java\eu\avalanche7\paradigm\platform\Interfaces" 2>nul

echo Cleaning forge-1.21.1...
rd /s /q "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\configs" 2>nul
rd /s /q "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\core" 2>nul
rd /s /q "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\data" 2>nul
rd /s /q "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\modules" 2>nul
rd /s /q "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\utils" 2>nul
rd /s /q "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\webeditor" 2>nul
rd /s /q "forge-1.21.1\src\main\java\eu\avalanche7\paradigm\platform\Interfaces" 2>nul

echo.
echo ================================================
echo Cleanup complete!
echo ================================================
echo.
echo Each version module now contains only:
echo  - Paradigm.java
echo  - platform/*.java (implementations)
echo  - mixin/ (if present)
echo.
echo All shared code is in: common/src/main/java/
echo.
pause

