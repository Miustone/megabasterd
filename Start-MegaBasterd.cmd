@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT_DIR=%~dp0"
if "%ROOT_DIR:~-1%"=="\" set "ROOT_DIR=%ROOT_DIR:~0,-1%"

set "JAR_PATH=%ROOT_DIR%\target\MegaBasterd-9.0.0-jar-with-dependencies.jar"
set "EXE_PATH=%ROOT_DIR%\target\dist\MegaBasterd\MegaBasterd.exe"
set "EXE_JAR=%ROOT_DIR%\target\dist\MegaBasterd\app\MegaBasterd-9.0.0-jar-with-dependencies.jar"
if exist "%EXE_PATH%" (
    set "USE_EXE=1"
    if exist "%JAR_PATH%" if exist "%EXE_JAR%" (
        for /f %%R in ('powershell -NoProfile -Command "if ((Get-Item '%JAR_PATH%').LastWriteTimeUtc -gt (Get-Item '%EXE_JAR%').LastWriteTimeUtc) { 'STALE' }"') do (
            if "%%R"=="STALE" set "USE_EXE=0"
        )
    )
    if "!USE_EXE!"=="1" (
        start "" "%EXE_PATH%" %*
        exit /b 0
    )
)

set "JAVAW_EXE="

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" (
    call :check_java_25 "%JAVA_HOME%\bin\java.exe" && set "JAVAW_EXE=%JAVA_HOME%\bin\javaw.exe"
)

if not defined JAVAW_EXE if exist "%USERPROFILE%\tools\jdk-25\bin\javaw.exe" (
    set "JAVAW_EXE=%USERPROFILE%\tools\jdk-25\bin\javaw.exe"
)

if not defined JAVAW_EXE (
    for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Java\jdk-25*" "%ProgramFiles%\Microsoft\jdk-25*" "%ProgramFiles%\Zulu\zulu-25*") do (
        if not defined JAVAW_EXE if exist "%%~fD\bin\javaw.exe" set "JAVAW_EXE=%%~fD\bin\javaw.exe"
    )
)

if not defined JAVAW_EXE (
    where javaw.exe >nul 2>nul
    if not errorlevel 1 (
        for /f "delims=" %%J in ('where java.exe 2^>nul') do (
            if not defined JAVAW_EXE (
                call :check_java_25 "%%~fJ"
                if not errorlevel 1 (
                    set "CANDIDATE_W=%%~dpJjavaw.exe"
                    if exist "!CANDIDATE_W!" set "JAVAW_EXE=!CANDIDATE_W!"
                )
            )
        )
    )
)

if not defined JAVAW_EXE (
    powershell -NoProfile -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('MegaBasterd 9.0 requires Java 25 LTS (64-bit). System PATH currently uses an older Java runtime (e.g. Zulu Java 11). Install Temurin JDK 25 or run scripts\build-windows-app.ps1 to build target\dist\MegaBasterd\MegaBasterd.exe.', 'MegaBasterd 9.0 - Java 25 Required', 'OK', 'Error') | Out-Null"
    exit /b 1
)

if not exist "%JAR_PATH%" (
    if exist "%ROOT_DIR%\mvnw.cmd" (
        for %%J in ("%JAVAW_EXE%") do set "JAVA_HOME=%%~dpJ.."
        call "%ROOT_DIR%\mvnw.cmd" -f "%ROOT_DIR%\pom.xml" -q -DskipTests package
    )
)

if not exist "%JAR_PATH%" (
    powershell -NoProfile -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('MegaBasterd JAR could not be found or built at target\MegaBasterd-9.0.0-jar-with-dependencies.jar.', 'MegaBasterd 9.0', 'OK', 'Error') | Out-Null"
    exit /b 1
)

start "" "%JAVAW_EXE%" --enable-native-access=ALL-UNNAMED -Djava.net.preferIPv6Addresses=system -Djdk.httpclient.keepalive.timeout=10 -jar "%JAR_PATH%" %*
exit /b 0

:check_java_25
set "VER_LINE="
for /f "tokens=3 delims= " %%V in ('"%~1" -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined VER_LINE set "VER_LINE=%%~V"
)
if not defined VER_LINE exit /b 1
for /f "tokens=1 delims=." %%M in ("%VER_LINE%") do set "MAJOR=%%M"
if "%MAJOR%"=="" exit /b 1
if %MAJOR% GEQ 25 exit /b 0
exit /b 1
