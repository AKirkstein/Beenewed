@echo off
setlocal
set "JAR=%~dp0target\beenewed-standalone.jar"
if not exist "%JAR%" (
    echo Building Beenewed standalone JAR...
    pushd "%~dp0"
    call mvn -Pstandalone package -DskipTests
    if errorlevel 1 (
        popd
        exit /b 1
    )
    popd
)
java -jar "%JAR%" %*
endlocal
