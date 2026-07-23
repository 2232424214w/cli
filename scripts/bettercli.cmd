@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem BetterCLI launcher for Windows — works from repo scripts\ or ~/.bettercli\bin.

where java >nul 2>&1
if errorlevel 1 (
  echo Java not found. BetterCLI requires Java 17+.
  exit /b 1
)

rem UTF-8 console so banner / emoji render correctly
chcp 65001 >nul 2>&1

rem IDEs often set TERM=dumb on Windows; that forces JLine into DumbTerminal.
if /I "%TERM%"=="dumb" set "TERM="
if /I "%TERM%"=="dumb-color" set "TERM="

set "JAR="
if defined BETTERCLI_JAR (
  set "JAR=%BETTERCLI_JAR%"
  goto :found
)

set "HERE=%~dp0"
set "HERE=%HERE:~0,-1%"

if exist "%HERE%\..\lib\bettercli.jar" (
  for %%I in ("%HERE%\..\lib\bettercli.jar") do set "JAR=%%~fI"
  goto :found
)

if exist "%USERPROFILE%\.bettercli\lib\bettercli.jar" (
  set "JAR=%USERPROFILE%\.bettercli\lib\bettercli.jar"
  goto :found
)

if exist "%HERE%\..\target\bettercli-1.0-SNAPSHOT.jar" (
  for %%I in ("%HERE%\..\target\bettercli-1.0-SNAPSHOT.jar") do set "JAR=%%~fI"
  goto :found
)

echo BetterCLI jar not found.
echo    Tried:
echo      - %HERE%\..\lib\bettercli.jar
echo      - %USERPROFILE%\.bettercli\lib\bettercli.jar
echo      - %HERE%\..\target\bettercli-1.0-SNAPSHOT.jar
echo    Fix: run scripts\install.ps1  (or set BETTERCLI_JAR)
exit /b 1

:found
if not exist "%JAR%" (
  echo BetterCLI jar not found: %JAR%
  exit /b 1
)

set "JVM_OPTS="
if defined BETTERCLI_JAVA_OPTS set "JVM_OPTS=%BETTERCLI_JAVA_OPTS%"
if not defined JVM_OPTS if defined JAVA_OPTS set "JVM_OPTS=%JAVA_OPTS%"

rem Java 22+: JLine JNI WinVT needs native access; without it the UI falls back to plain.
java --enable-native-access=ALL-UNNAMED %JVM_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
