@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PROJECT_ROOT=%~dp0"
cd /d "%PROJECT_ROOT%"

set "EXITCODE=0"

set "SRC_DIR=%PROJECT_ROOT%src\main\java"
set "RES_DIR1=%PROJECT_ROOT%src\main\resources"
set "RES_DIR2=%PROJECT_ROOT%resources"
set "LIB_DIR=%PROJECT_ROOT%lib"
set "BUILD_DIR=%PROJECT_ROOT%build"
set "CLASSES_DIR=%BUILD_DIR%\classes"
set "BUILD_LIB_DIR=%BUILD_DIR%\lib"

echo ============================================================
echo Building project...
echo Project: %PROJECT_ROOT%
echo Output : %CLASSES_DIR%
echo ============================================================
echo.

if exist "%CLASSES_DIR%" rmdir /s /q "%CLASSES_DIR%" >nul 2>nul
if exist "%BUILD_LIB_DIR%" rmdir /s /q "%BUILD_LIB_DIR%" >nul 2>nul

mkdir "%CLASSES_DIR%" 2>nul
mkdir "%BUILD_LIB_DIR%" 2>nul

set "JAVAC=javac"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"

"%JAVAC%" -version >nul 2>nul
if errorlevel 1 (
  echo ERROR: javac not found.
  echo Install JDK 21 and set JAVA_HOME or add javac to PATH.
  set "EXITCODE=1"
  goto :END
)

java -version >nul 2>nul
if errorlevel 1 (
  echo ERROR: java runtime not found.
  echo Install JDK 21 and set JAVA_HOME or add java to PATH.
  set "EXITCODE=1"
  goto :END
)

set "CP="
if exist "%LIB_DIR%\*.jar" (
  echo Copying jars to %BUILD_LIB_DIR% ...
  for %%f in ("%LIB_DIR%\*.jar") do (
    if defined CP (
      set "CP=!CP!;%%~ff"
    ) else (
      set "CP=%%~ff"
    )
  )
  copy /y "%LIB_DIR%\*.jar" "%BUILD_LIB_DIR%\" >nul 2>nul
  echo Jars copied.
  echo.
) else (
  echo No jars found under %LIB_DIR% .
  echo.
)

if not exist "%SRC_DIR%" (
  echo ERROR: source directory not found: %SRC_DIR%
  set "EXITCODE=1"
  goto :END
)

set "SOURCES_LIST=%TEMP%\llama_java_sources_%RANDOM%_%RANDOM%.txt"
if exist "%SOURCES_LIST%" del /q "%SOURCES_LIST%" >nul 2>nul
for /r "%SRC_DIR%" %%f in (*.java) do echo %%f>>"%SOURCES_LIST%"

for %%A in ("%SOURCES_LIST%") do if %%~zA==0 (
  echo ERROR: no .java files found under: %SRC_DIR%
  set "EXITCODE=1"
  goto :END
)

echo Compiling Java sources...
if defined CP (
  "%JAVAC%" --release 21 -encoding UTF-8 -d "%CLASSES_DIR%" -cp "%CP%" @"%SOURCES_LIST%"
) else (
  "%JAVAC%" --release 21 -encoding UTF-8 -d "%CLASSES_DIR%" @"%SOURCES_LIST%"
)
if errorlevel 1 (
  echo.
  echo ERROR: compilation failed. Exit code: %errorlevel%
  set "EXITCODE=%errorlevel%"
  goto :END
)
echo Compilation succeeded.
echo.

set "COPIED_RES=0"
if exist "%RES_DIR1%\" (
  echo Copying resources to %CLASSES_DIR% ...
  xcopy "%RES_DIR1%\*" "%CLASSES_DIR%\" /E /I /Y /H >nul
  set "COPIED_RES=1"
)
if exist "%RES_DIR2%\" (
  if "%COPIED_RES%"=="0" echo Copying resources to %CLASSES_DIR% ...
  xcopy "%RES_DIR2%\*" "%CLASSES_DIR%\" /E /I /Y /H >nul
  set "COPIED_RES=1"
)
if "%COPIED_RES%"=="1" (
  echo Resources copied.
  echo.
) else (
  echo No resources directory found: %RES_DIR1%
  echo No resources directory found: %RES_DIR2%
  echo.
)

set "RUN_BAT=%BUILD_DIR%\run.bat"
> "%RUN_BAT%" echo @echo off
>>"%RUN_BAT%" echo setlocal EnableExtensions
>>"%RUN_BAT%" echo cd /d "%%~dp0"
>>"%RUN_BAT%" echo start "" javaw.exe -Xms128m -Xmx128m -classpath "./classes;./lib/*" org.mark.llamacpp.server.LlamaServer %*
>>"%RUN_BAT%" echo endlocal

:END
if exist "%SOURCES_LIST%" del /q "%SOURCES_LIST%" >nul 2>nul

echo ============================================================
if "%EXITCODE%"=="0" (
  echo Build SUCCESS.
  echo Build output: %CLASSES_DIR%
  echo Run script  : %RUN_BAT%
) else (
  echo Build FAILED.
  echo Exit code: %EXITCODE%
)
echo ============================================================
:NO_PAUSE_CHECK
if /I "%CI%"=="true" goto :NO_PAUSE
if /I "%NO_PAUSE%"=="1" goto :NO_PAUSE
echo Press any key to close this window...
pause >nul
:NO_PAUSE
endlocal & exit /b %EXITCODE%
