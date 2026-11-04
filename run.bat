@echo off
rem Runs CircularSync from out\ (build first with build.bat).
rem Any arguments are passed through, for example:
rem   run.bat daemon --today 2026-11-04
rem   run.bat query top 3
setlocal
cd /d "%~dp0"

if defined JAVA_HOME (
  set "JAVA=%JAVA_HOME%\bin\java"
) else (
  set "JAVA=java"
)

if not exist out\com\circularsync\main\Main.class (
  echo CircularSync is not built yet. Run build.bat first.
  exit /b 1
)

"%JAVA%" -cp out com.circularsync.main.Main %*
endlocal
