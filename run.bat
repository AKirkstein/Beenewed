@echo off
REM Compile and run Beenewed from source (Java 17+).
REM Uses paths relative to this folder, so it works even if the folder name has spaces.
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
javac -d out src\beenewed\*.java src\beenewed\model\*.java src\beenewed\ui\*.java src\beenewed\screens\*.java src\beenewed\dialogs\*.java
if errorlevel 1 (
  echo.
  echo Compilation failed.
  pause
  exit /b 1
)
java -cp out beenewed.App
