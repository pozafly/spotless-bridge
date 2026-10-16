@echo off
rem Spotless Bridge hook launcher. The Gradle plugin writes it to <gradle-root>\.gradle\spotless-bridge\
rem on every Gradle run, so it always matches the plugin version.
rem Usage: .gradle\spotless-bridge\spotless-bridge.cmd [files...]
rem Files are relative to the Gradle root or absolute. Formatting logic lives in the project's engine.
setlocal
if "%~1"=="" exit /b 0
for %%R in ("%~dp0..\..") do set "BRIDGE_ROOT=%%~fR"
set "BRIDGE_NATIVE="
if exist "%BRIDGE_ROOT%\.gradle\spotless-bridge\native-client.txt" set /p BRIDGE_NATIVE=<"%BRIDGE_ROOT%\.gradle\spotless-bridge\native-client.txt"
if defined BRIDGE_NATIVE set "BRIDGE_NATIVE=%BRIDGE_ROOT%\.gradle\spotless-bridge\%BRIDGE_NATIVE%"
if defined BRIDGE_NATIVE if exist "%BRIDGE_NATIVE%" goto native
set "BRIDGE_RUNTIME=%BRIDGE_ROOT%\.gradle\spotless-bridge\client-runtime.txt"
rem A missing installed native client is restored by Gradle before handling Unicode file arguments.
if defined BRIDGE_NATIVE goto prepare
call :read
if exist "%BRIDGE_JAR%" if exist "%BRIDGE_JAVA%\bin\java.exe" goto run
rem First use, or the engine was removed with part of .gradle: Gradle prepares the project's engine again.
:prepare
pushd "%BRIDGE_ROOT%" || exit /b 1
call gradlew.bat --console=plain --no-configuration-cache spotlessBridgeStart
if errorlevel 1 (
    popd
    exit /b 1
)
popd
if exist "%BRIDGE_ROOT%\.gradle\spotless-bridge\native-client.txt" set /p BRIDGE_NATIVE=<"%BRIDGE_ROOT%\.gradle\spotless-bridge\native-client.txt"
if defined BRIDGE_NATIVE set "BRIDGE_NATIVE=%BRIDGE_ROOT%\.gradle\spotless-bridge\%BRIDGE_NATIVE%"
if defined BRIDGE_NATIVE if exist "%BRIDGE_NATIVE%" goto native
call :read
:run
"%BRIDGE_JAVA%\bin\java.exe" -cp "%BRIDGE_JAR%" io.github.pozafly.spotlessbridge.GradleFormatter --hook "%BRIDGE_ROOT%" %*
exit /b %ERRORLEVEL%

:native
"%BRIDGE_NATIVE%" --root "%BRIDGE_ROOT%" apply -- %*
exit /b %ERRORLEVEL%

:read
set "BRIDGE_JAR="
set "BRIDGE_JAVA="
if not exist "%BRIDGE_RUNTIME%" exit /b 0
set /p BRIDGE_JAR=<"%BRIDGE_RUNTIME%"
for /f "usebackq skip=1 delims=" %%L in ("%BRIDGE_RUNTIME%") do if not defined BRIDGE_JAVA set "BRIDGE_JAVA=%%L"
exit /b 0
