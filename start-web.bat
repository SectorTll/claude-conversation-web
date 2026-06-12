@echo off
REM ==========================================================================
REM  Claude Conversation Web - production start
REM  Builds the Vue app into the jar and runs it as a single process over HTTPS.
REM  Open https://127.0.0.1:8443 (a browser tab opens automatically on Windows).
REM ==========================================================================
setlocal
cd /d "%~dp0"

REM HTTPS keystore: generate a self-signed cert on first run (pass your LAN IP to gen-cert.bat
REM directly to avoid the host-mismatch warning on other machines).
if not exist "certs\keystore.p12" (
    echo No TLS keystore found - generating a self-signed cert ...
    call "scripts\gen-cert.bat"
)

REM Login password (shared). Required: the server refuses to start without it.
if "%CLAUDE_SECURITY_PASSWORD%"=="" set /p CLAUDE_SECURITY_PASSWORD=Set a login password for this session:
if "%CLAUDE_SECURITY_PASSWORD%"=="" (
    echo A password is required. Set CLAUDE_SECURITY_PASSWORD and retry.
    pause
    exit /b 1
)

echo.
echo === Building (frontend + backend jar) ... this may take a minute ===
call gradlew.bat bootJar --console=plain
if errorlevel 1 (
    echo.
    echo Build FAILED. See the output above.
    pause
    exit /b 1
)

set "JAR="
for /f "delims=" %%f in ('dir /b /a-d "build\libs\*.jar" ^| findstr /v /i "plain"') do set "JAR=build\libs\%%f"

if not defined JAR (
    echo Could not find the built jar in build\libs.
    pause
    exit /b 1
)

echo.
echo === Starting %JAR% ===
echo     URL:  https://0.0.0.0:8443   (self-signed cert - accept the browser warning once)
echo     LAN:  https://^<this-machine-ip^>:8443   (allow client IPs via claude.security.allowed-cidrs)
echo     Stop: press Ctrl+C in this window
echo.
java -jar "%JAR%"

echo.
echo Server stopped.
pause
endlocal
