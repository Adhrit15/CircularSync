@echo off
rem Compiles CircularSync and its test suite into out\ and runs the tests.
rem Uses %JAVA_HOME%\bin when JAVA_HOME is set, otherwise javac/java from PATH.
setlocal
cd /d "%~dp0"

if defined JAVA_HOME (
  set "JAVAC=%JAVA_HOME%\bin\javac"
  set "JAVA=%JAVA_HOME%\bin\java"
) else (
  set "JAVAC=javac"
  set "JAVA=java"
)

if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java test\*.java > out\sources.txt
"%JAVAC%" --release 17 -Xlint:all -encoding UTF-8 -d out @out\sources.txt
if errorlevel 1 exit /b 1
echo Compiled sources into out\

if /i not "%~1"=="--skip-tests" (
  "%JAVA%" -cp out com.circularsync.test.CircularSyncTestSuite
  if errorlevel 1 exit /b 1
)
endlocal
