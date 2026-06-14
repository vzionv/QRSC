@echo off
setlocal

cd /d "%~dp0"

if not exist gradlew (
    echo [ERROR] gradlew not found. Run this script from the project root.
    exit /b 1
)

set BUILD_TYPE=debug
if not "%1"=="" set BUILD_TYPE=%1

if /i "%BUILD_TYPE%"=="debug" (
    set GRADLE_TASK=assembleDebug
    set APK_PATH=app\build\outputs\apk\debug
    set APK_FILE=app-debug.apk
) else if /i "%BUILD_TYPE%"=="release" (
    set GRADLE_TASK=assembleRelease
    set APK_PATH=app\build\outputs\apk\release
    set APK_FILE=app-release.apk
) else (
    echo [ERROR] Invalid build type: %BUILD_TYPE%. Use "debug" or "release".
    exit /b 1
)

echo Building %BUILD_TYPE% APK...
echo.
call gradlew %GRADLE_TASK% --no-daemon
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Build failed.
    exit /b 1
)

echo.
echo ============================================================
echo Build successful!
echo.

for /r "%APK_PATH%" %%f in (*.apk) do (
    echo APK: %%f
    echo Size: %%~zf bytes
)

echo.
echo Install: adb install -r %APK_PATH%\%APK_FILE%

endlocal
