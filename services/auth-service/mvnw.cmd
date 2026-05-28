@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.2
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set ERROR_CODE=0

if not "%JAVA_HOME%"=="" goto OkJHome
set JAVACMD=java.exe
goto runMaven

:OkJHome
set JAVACMD=%JAVA_HOME%\bin\java.exe

:runMaven
set WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar
if exist "%WRAPPER_JAR%" goto launch

for /f "tokens=2 delims==" %%a in ('findstr /b "wrapperUrl=" "%~dp0.mvn\wrapper\maven-wrapper.properties"') do set WRAPPER_URL=%%a
echo Downloading Maven Wrapper from %WRAPPER_URL%
powershell -Command "Invoke-WebRequest -Uri %WRAPPER_URL% -OutFile %WRAPPER_JAR%"

:launch
"%JAVACMD%" -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%~dp0" org.apache.maven.wrapper.MavenWrapperMain %*
set ERROR_CODE=%ERRORLEVEL%
exit /b %ERROR_CODE%
