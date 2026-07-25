@echo off
REM Builds and runs the portable GPU texture-capability probe against a Starsector installation,
REM using the game's own LWJGL and its own bundled JVM so the context measured is the one the game
REM gets. Read-only: the game is never launched and nothing in the installation is modified.
setlocal enabledelayedexpansion

set "HERE=%~dp0"
set "HERE=%HERE:~0,-1%"

if not "%~1"=="" (
    set "INSTALL=%~1"
) else if exist "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core" (
    set "INSTALL=C:\Program Files (x86)\Fractal Softworks\Starsector"
) else if exist "C:\Program Files\Fractal Softworks\Starsector\starsector-core" (
    set "INSTALL=C:\Program Files\Fractal Softworks\Starsector"
) else (
    echo No Starsector installation found. Pass its path as the first argument, e.g.
    echo   %~nx0 "C:\Program Files ^(x86^)\Fractal Softworks\Starsector"
    exit /b 2
)

set "JARS=%INSTALL%\starsector-core"
set "NATIVES=%JARS%\native\windows"
set "LWJGL=%JARS%\lwjgl.jar"
set "BUNDLED_JAVA=%INSTALL%\jre\bin\java.exe"

if not exist "%LWJGL%" (
    echo Missing %LWJGL%
    exit /b 2
)
if not exist "%NATIVES%" (
    echo Missing native library directory: %NATIVES%
    exit /b 2
)
if not exist "%BUNDLED_JAVA%" (
    echo Bundled JVM not found at %BUNDLED_JAVA%; falling back to java on PATH.
    set "BUNDLED_JAVA=java"
)

where javac >nul 2>&1
if errorlevel 1 (
    echo javac was not found. A JDK 17 or newer is needed to build the probe.
    exit /b 2
)

REM Not wmic: it was removed in recent Windows 11 builds. PowerShell has shipped since Windows 7.
set "STAMP="
for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss" 2^>nul') do set "STAMP=%%I"
if not defined STAMP set "STAMP=latest"
set "BUILD=%HERE%\.probe-build"
set "REPORT=%HERE%\gpu-capability-report-%STAMP%.txt"
if not exist "%BUILD%" mkdir "%BUILD%"

javac --release 17 -nowarn -cp "%LWJGL%" -d "%BUILD%" "%HERE%\GpuCapabilityProbe.java"
if errorlevel 1 (
    echo Probe failed to build.
    exit /b 2
)

REM The block-upload probe additionally needs preflight's own encoder, since the point is to compare
REM its output against the driver's decoder. Skipped rather than fatal when the module is unbuilt,
REM so the capability probe still runs on a machine with only a JDK and the game.
set "CORE_CLASSES=%HERE%\..\..\preflight-core\target\classes"
set "BLOCK_PROBE=no"
if exist "%CORE_CLASSES%\" (
    javac --release 17 -nowarn -cp "%LWJGL%;%CORE_CLASSES%" -d "%BUILD%" "%HERE%\BlockUploadProbe.java"
    if errorlevel 1 (
        echo Block-upload probe failed to build; continuing with the capability probe only.
    ) else (
        set "BLOCK_PROBE=yes"
    )
)

(
    echo Starsector Preflight - GPU texture capability probe
    echo installation: %INSTALL%
    echo probe JVM:    %BUNDLED_JAVA%
    echo.
    "%BUNDLED_JAVA%" -cp "%LWJGL%;%BUILD%" "-Djava.library.path=%NATIVES%" GpuCapabilityProbe
    echo.
    echo == encoder vs driver: does the hardware read the blocks we write?
    if "%BLOCK_PROBE%"=="yes" (
        "%BUNDLED_JAVA%" -cp "%LWJGL%;%CORE_CLASSES%;%BUILD%" "-Djava.library.path=%NATIVES%" BlockUploadProbe
    ) else (
        echo skipped ^(build preflight-core first: mvn -q -pl preflight-core -am install -DskipTests^)
    )
) > "%REPORT%" 2>&1

type "%REPORT%"
echo.
echo Report written to: %REPORT%
endlocal
