@echo off
REM ==========================================================================
REM  Claude Conversation Web - development start
REM  Launches the Spring Boot backend (:8080) and the Vite dev server (:5173)
REM  in two separate windows. Vite hot-reloads the UI and proxies /api + /sse
REM  to the backend. Use the :5173 tab (it opens automatically).
REM ==========================================================================
setlocal
cd /d "%~dp0"

echo Starting backend (Spring Boot, :8080) and frontend (Vite, :5173) ...

REM Backend: 'dev' profile = plain http on :8080. Login wall is on.
REM bootRun also builds the chat sidecar (sidecar/dist/sidecar.mjs) via the sidecarBuild task;
REM the backend picks that file up directly in dev (no jar extraction).
REM Login password (shared). Prompt if not already set; the backend window inherits it.
if "%CLAUDE_SECURITY_PASSWORD%"=="" set /p CLAUDE_SECURITY_PASSWORD=Set a login password for this dev session:
if "%CLAUDE_SECURITY_PASSWORD%"=="" (
    echo A password is required. Set CLAUDE_SECURITY_PASSWORD and retry.
    exit /b 1
)
start "Claude Web - backend :8080" /D "%~dp0" cmd /k "set CLAUDE_AUTO_OPEN_BROWSER=false&& set SPRING_PROFILES_ACTIVE=dev&& gradlew.bat bootRun --console=plain"

REM Frontend: install deps on first run, then start Vite and open the browser.
start "Claude Web - frontend :5173" /D "%~dp0frontend" cmd /k "npm install --no-fund --no-audit && npm run dev -- --open"

echo.
echo   Backend:  http://127.0.0.1:8080   (API + SSE)
echo   Frontend: http://localhost:5173   (the dev UI - use this one)
echo   Login:    use the password you set above
echo.
echo Close the two opened windows to stop the servers.
endlocal
