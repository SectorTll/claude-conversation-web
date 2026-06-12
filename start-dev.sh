#!/usr/bin/env bash
# ==========================================================================
#  Claude Conversation Web - development start (macOS / Linux)
#  Launches the Spring Boot backend (:8080, 'dev' profile, plain http) and the
#  Vite dev server (:5173). Vite hot-reloads the UI and proxies /api + /sse to
#  the backend, and opens the browser. Ctrl+C stops both.
# ==========================================================================
set -euo pipefail
cd "$(dirname "$0")"

# Login password (shared). Prompt if not already set; both servers inherit it.
if [ -z "${CLAUDE_SECURITY_PASSWORD:-}" ]; then
  read -r -p "Set a login password for this dev session: " CLAUDE_SECURITY_PASSWORD
  export CLAUDE_SECURITY_PASSWORD
fi
if [ -z "${CLAUDE_SECURITY_PASSWORD:-}" ]; then
  echo "A password is required. Set CLAUDE_SECURITY_PASSWORD and retry." >&2
  exit 1
fi

export SPRING_PROFILES_ACTIVE=dev
export CLAUDE_AUTO_OPEN_BROWSER=false   # the Vite :5173 tab is the one to use

echo "Starting backend (Spring Boot, :8080) ..."
./gradlew bootRun --console=plain &
BACKEND_PID=$!
trap 'echo; echo "Stopping backend ..."; kill "$BACKEND_PID" 2>/dev/null || true' EXIT INT TERM

echo "Starting frontend (Vite, :5173) ..."
echo
echo "  Backend:  http://127.0.0.1:8080   (API + SSE)"
echo "  Frontend: http://localhost:5173   (the dev UI - use this one)"
echo "  Login:    use the password you set above"
echo
cd frontend
npm install --no-fund --no-audit
npm run dev -- --open
# When Vite exits, the EXIT trap stops the backend.
