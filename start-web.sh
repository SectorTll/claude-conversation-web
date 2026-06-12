#!/usr/bin/env bash
# ==========================================================================
#  Claude Conversation Web - production start (macOS / Linux)
#  Builds the Vue app into the jar and runs it as a single process over HTTPS.
#  Open https://127.0.0.1:8443 (accept the self-signed warning once).
# ==========================================================================
set -euo pipefail
cd "$(dirname "$0")"

# HTTPS keystore: generate a self-signed cert on first run (pass your LAN IP to gen-cert.sh
# directly to avoid the host-mismatch warning on other machines).
if [ ! -f certs/keystore.p12 ]; then
  echo "No TLS keystore found - generating a self-signed cert ..."
  ./scripts/gen-cert.sh
fi

# Login password (shared). Required: the server refuses to start without it.
if [ -z "${CLAUDE_SECURITY_PASSWORD:-}" ]; then
  read -r -p "Set a login password for this session: " CLAUDE_SECURITY_PASSWORD
  export CLAUDE_SECURITY_PASSWORD
fi
if [ -z "${CLAUDE_SECURITY_PASSWORD:-}" ]; then
  echo "A password is required. Set CLAUDE_SECURITY_PASSWORD and retry." >&2
  exit 1
fi

echo
echo "=== Building (frontend + backend jar) ... this may take a minute ==="
./gradlew bootJar --console=plain

# Pick the runnable jar (exclude the '-plain' classpath jar).
JAR="$(ls -1 build/libs/*.jar 2>/dev/null | grep -v -- '-plain' | head -n1 || true)"
if [ -z "$JAR" ]; then
  echo "Could not find the built jar in build/libs." >&2
  exit 1
fi

echo
echo "=== Starting $JAR ==="
echo "    URL:  https://127.0.0.1:8443   (self-signed cert - accept the browser warning once)"
echo "    LAN:  https://<this-machine-ip>:8443   (clients must be on an allowed CIDR)"
echo "    Stop: press Ctrl+C in this window"
echo
java -jar "$JAR"
