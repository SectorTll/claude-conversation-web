#!/usr/bin/env bash
# ==========================================================================
#  Generate a self-signed TLS keystore (certs/keystore.p12) for HTTPS.
#
#  Usage:   scripts/gen-cert.sh [IP1] [IP2] ...
#    IPn   the machine's address(es) clients will type in the URL bar.
#          Pass EVERY address you reach the server by (LAN IP, VPN IP, ...)
#          so no client gets a host mismatch.
#          127.0.0.1, localhost and ::1 are always included.
#          Example:  scripts/gen-cert.sh 192.168.1.10 10.0.0.5
#
#  The cert is still self-signed (an "untrusted issuer" warning until a
#  client trusts it — the in-app 📲 dialog serves a one-click installer).
#
#  Keystore password: set CLAUDE_SSL_KEYSTORE_PASSWORD, else "changeit".
#  The keystore is git-ignored - regenerate it on each machine.
# ==========================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

STOREPASS="${CLAUDE_SSL_KEYSTORE_PASSWORD:-changeit}"

# Build the SAN from all IP args (loopback + localhost always present).
SAN="ip:127.0.0.1,dns:localhost,ip:0:0:0:0:0:0:0:1"
EXTRA=""
for ip in "$@"; do
  SAN="$SAN,ip:$ip"
  EXTRA="$EXTRA $ip"
done
if [ -z "$EXTRA" ]; then
  echo "NOTE: no LAN IP given - the cert will only match 127.0.0.1 / localhost."
  echo "      Pass your LAN/VPN IP(s) as arguments so other machines connect without a host mismatch."
fi

# Find keytool: PATH first, then JAVA_HOME.
KEYTOOL="keytool"
if ! command -v keytool >/dev/null 2>&1; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
  else
    echo "Could not find keytool on PATH or via JAVA_HOME. Install a JDK or set JAVA_HOME." >&2
    exit 1
  fi
fi

mkdir -p certs
if [ -f certs/keystore.p12 ]; then
  echo "certs/keystore.p12 already exists - delete it first to regenerate."
  exit 0
fi

echo "Generating self-signed cert for 127.0.0.1, localhost, ::1$EXTRA ..."
"$KEYTOOL" -genkeypair -alias claudeweb -keyalg RSA -keysize 2048 -validity 3650 \
  -storetype PKCS12 -keystore certs/keystore.p12 -storepass "$STOREPASS" \
  -dname "CN=claude-conversation-web, O=doniss, C=EE" \
  -ext "SAN=$SAN"

echo "Done: certs/keystore.p12  (alias=claudeweb, storepass from CLAUDE_SSL_KEYSTORE_PASSWORD or \"changeit\")"
