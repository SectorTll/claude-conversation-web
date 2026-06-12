@echo off
REM ==========================================================================
REM  Generate a self-signed TLS keystore (certs\keystore.p12) for HTTPS.
REM
REM  Usage:   scripts\gen-cert.bat [IP1] [IP2] ...
REM    IPn   the machine's address(es) clients will type in the URL bar.
REM          Pass EVERY address you reach the server by (LAN IP, VPN IP, ...)
REM          so no client gets a host mismatch.
REM          127.0.0.1, localhost and ::1 are always included.
REM          Example:  scripts\gen-cert.bat 192.168.1.10 10.0.0.5
REM
REM  The cert is still self-signed (an "untrusted issuer" warning until a
REM  client trusts it — the in-app 📲 dialog serves a one-click installer).
REM
REM  Keystore password: set CLAUDE_SSL_KEYSTORE_PASSWORD, else "changeit".
REM  The keystore is git-ignored - regenerate it on each machine.
REM ==========================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set "STOREPASS=%CLAUDE_SSL_KEYSTORE_PASSWORD%"
if "%STOREPASS%"=="" set "STOREPASS=changeit"

REM Build the SAN from all IP args (loopback + localhost always present).
set "SAN=ip:127.0.0.1,dns:localhost,ip:0:0:0:0:0:0:0:1"
set "IPLIST="
for %%a in (%*) do (
    set "SAN=!SAN!,ip:%%a"
    set "IPLIST=!IPLIST! %%a"
)
if "%IPLIST%"=="" (
    echo NOTE: no LAN IP given - the cert will only match 127.0.0.1 / localhost.
    echo       Pass your LAN/VPN IP^(s^) as arguments so other machines connect without a host mismatch.
)

REM Find keytool: PATH first, then JAVA_HOME.
set "KEYTOOL=keytool"
where keytool >nul 2>nul
if errorlevel 1 (
    if defined JAVA_HOME (
        set "KEYTOOL=%JAVA_HOME%\bin\keytool"
    ) else (
        echo Could not find keytool on PATH or via JAVA_HOME. Install a JDK or set JAVA_HOME.
        exit /b 1
    )
)

if not exist "certs" mkdir "certs"
if exist "certs\keystore.p12" (
    echo certs\keystore.p12 already exists - delete it first to regenerate.
    exit /b 0
)

echo Generating self-signed cert for 127.0.0.1, localhost, ::1!IPLIST! ...
"%KEYTOOL%" -genkeypair -alias claudeweb -keyalg RSA -keysize 2048 -validity 3650 ^
  -storetype PKCS12 -keystore "certs\keystore.p12" -storepass "%STOREPASS%" ^
  -dname "CN=claude-conversation-web, O=doniss, C=EE" ^
  -ext "SAN=!SAN!"

if errorlevel 1 (
    echo Certificate generation FAILED.
    exit /b 1
)
echo Done: certs\keystore.p12  (alias=claudeweb, storepass from CLAUDE_SSL_KEYSTORE_PASSWORD or "changeit")
endlocal
