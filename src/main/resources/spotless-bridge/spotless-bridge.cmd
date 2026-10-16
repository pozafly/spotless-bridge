@echo off
rem Spotless Bridge hook launcher, written by gradlew spotlessBridgeInstallHook.
rem Usage: spotless-bridge.cmd <gradle-root> [files...]
rem Files are relative to the Gradle root or absolute. Formatting logic lives in the project's engine,
rem so this launcher only needs reinstalling when Spotless Bridge warns about it.
setlocal
if "%~1"=="" (
    echo Usage: spotless-bridge.cmd ^<gradle-root^> [files...] 1>&2
    exit /b 2
)
if "%~2"=="" exit /b 0
set "BRIDGE_ROOT=%~f1"
set "BRIDGE_RUNTIME=%BRIDGE_ROOT%\.gradle\spotless-bridge\client-runtime.txt"
call :read
if exist "%BRIDGE_JAR%" if exist "%BRIDGE_JAVA%\bin\java.exe" goto run
rem First use, or the engine was removed with part of .gradle: Gradle prepares the project's engine again.
pushd "%BRIDGE_ROOT%" || exit /b 1
call gradlew.bat --console=plain --no-configuration-cache spotlessBridgeStart
if errorlevel 1 (
    popd
    exit /b 1
)
popd
call :read
:run
"%BRIDGE_JAVA%\bin\java.exe" -cp "%BRIDGE_JAR%" io.github.pozafly.spotlessbridge.GradleFormatter --hook %*
exit /b %ERRORLEVEL%

:read
set "BRIDGE_JAR="
set "BRIDGE_JAVA="
if not exist "%BRIDGE_RUNTIME%" exit /b 0
set /p BRIDGE_JAR=<"%BRIDGE_RUNTIME%"
for /f "usebackq skip=1 delims=" %%L in ("%BRIDGE_RUNTIME%") do if not defined BRIDGE_JAVA set "BRIDGE_JAVA=%%L"
exit /b 0
