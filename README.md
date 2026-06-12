# Claude Conversation Web

A local web app for your Claude Code CLI session store (`~/.claude`): browse, search, rename,
schedule, monitor — and **chat in** your conversations right from the browser. Started as the web
port of a desktop viewer and grew well past it.

Backend: **Spring Boot 4 / Java 21**. Frontend: **Vue 3 + Vite + TypeScript + Pinia**. Chat sidecar:
**Node + `@anthropic-ai/claude-agent-sdk`**. Runs on your machine and can be reached from a trusted
LAN — protected by a shared-password login, HTTPS (self-signed), and a client-IP allowlist.

## Features

- **3-pane browser** — projects → sessions → conversation, with Markdown rendering, tool blocks
  (Edit/Write calls render as **real diffs** with +/- coloring), per-session **token totals and
  estimated cost** (configurable `claude.pricing`), global search (**Lucene-indexed** on large
  stores, with an exact brute-force fallback) and in-conversation search, and rename at the Claude
  level (custom titles written back to the `.jsonl`).
- **Fork from any message** — the ⑂ button on a message copies the conversation up to that point
  into a new session, so you can branch an exploration without losing the original.
- **Chat in the browser.** Continue any session (or start a new one) without opening a terminal.
  Two engines (`claude.chat.engine`):
  - **`sdk`** (default) — a persistent Node sidecar per active session built on the Claude Agent
    SDK. No per-turn cold start; tool permissions appear as **interactive Allow/Deny cards** (with
    "Always allow" persisting the SDK's suggested rules, and an inline diff for Edit/Write asks);
    `AskUserQuestion` is answered within the same turn; live thinking and text streaming;
    **paste/drop images** into the composer. Per-turn **model picker**
    (`haiku | sonnet | opus | fable`) and **permission-mode picker**.
  - **`cli`** (fallback) — a fresh headless `claude -p` process per turn; permissions are frozen per
    turn, questions end the turn and your pick becomes the next one. Used automatically if Node or
    the sidecar bundle is missing.

  Either way the `claude` CLI itself writes the canonical session `.jsonl`, so everything you do in
  the browser shows up in the terminal CLI and vice versa.
- **Live status over SSE** — green *working* / orange *waiting* badges per project and session. A
  chat turn parked on a permission/question card is reported as **waiting**, the pending cards
  survive page reloads and second tabs, and an optional notification pings background tabs.
- **Push notifications when Claude is waiting** — even with the tab closed: **Web Push** (service
  worker; needs the self-signed cert trusted on the client) and/or a **Telegram bot** message with
  a deep link (no certificate requirements). One ping per card, per-session cooldown against bursts.
  Trusting the cert is one click: the 📲 button opens a helper that serves the certificate and a
  ready-made `install-cert.bat` straight from the server (`/cert/…`). Telegram is configured right in
  the app — the **✈ button** opens a settings dialog (enable, bot token, chat id, optional deep-link
  origin, and a **Send test** button); changes apply live and persist under `_claude-telegram/`.
- **Two schedulers:**
  - *OS scheduler* (`/api/schedule`) — registers headless Claude runs in the **Windows Task
    Scheduler** (Windows-only; hidden elsewhere).
  - *Server scheduler* (`/api/server-schedule`) — the always-on server fires `claude -p` runs itself
    on a cron (daily/weekly/once), writes Markdown reports, and re-arms on restart.
    **Cross-platform.**
- **OS integration** on Windows, macOS and Linux — open a terminal running `claude` in the
  project's directory (resume / fork / new session), reveal in Explorer / Finder / the freedesktop
  file manager.
- `claude.excluded-projects` hides chosen projects from the UI without touching them on disk.

## Requirements

- JDK 21+
- Node 20+ (frontend build + the chat sidecar)
- The `claude` CLI on `PATH`

## Quick start (Windows)

Double-click one of:

- **`start-web.bat`** — production: generates a TLS cert if missing, prompts for a password, builds
  the jar and runs it over HTTPS; opens <https://127.0.0.1:8443> (accept the self-signed warning).
- **`start-dev.bat`** — development: launches the backend (:8080, `dev` profile, plain http) and the
  Vite dev server (:5173) with hot reload; opens <http://localhost:5173>. Login password is `dev`.

## Quick start (macOS / Linux)

The same two flows, as shell scripts (run from the project root):

```sh
./start-web.sh        # production: cert + password prompt + build + run over HTTPS (:8443)
./start-dev.sh        # development: backend :8080 + Vite :5173 (hot reload), Ctrl+C stops both
```

If they aren't executable yet: `chmod +x start-web.sh start-dev.sh scripts/gen-cert.sh`.

## Run (packaged — single process)

```sh
# Windows
scripts\gen-cert.bat 192.168.1.10       # one-time: self-signed keystore (use your LAN IP)
set CLAUDE_SECURITY_PASSWORD=<password>
./gradlew bootJar
java -jar build/libs/claude-conversation-web-0.0.1-SNAPSHOT.jar

# macOS / Linux
scripts/gen-cert.sh 192.168.1.10        # one-time: self-signed keystore (use your LAN IP)
export CLAUDE_SECURITY_PASSWORD=<password>
./gradlew bootJar
java -jar build/libs/claude-conversation-web-0.0.1-SNAPSHOT.jar
```

Then open <https://127.0.0.1:8443> (auto-opens on Windows / macOS / Linux; accept the self-signed
warning). The Vue app and the sidecar bundle are baked into the jar, so it's one process and one
origin. Build flags: `-PskipFrontend` skips the Vue build, `-PskipSidecar` skips the sidecar build
(the `sdk` chat engine then needs an existing bundle, or set `claude.chat.engine=cli`).

## Run (dev — hot reload)

```sh
# Windows uses `set NAME=value`; macOS / Linux use `export NAME=value`.
set SPRING_PROFILES_ACTIVE=dev
set CLAUDE_SECURITY_PASSWORD=dev
./gradlew bootRun                 # backend on :8080 (plain http, IP allowlist off)
cd frontend && npm install && npm run dev   # SPA on :5173, proxies /api + /sse to :8080
```

Open <http://localhost:5173> and log in with `dev`.

## Tests

```sh
./gradlew test                    # backend (add -PskipFrontend / -PskipSidecar to skip npm builds)
cd frontend && npm run test       # frontend store tests (live merge, chat, permission cards)
cd sidecar && npm test            # sidecar protocol + canUseTool bridge
```

## Configuration

Override in `application.yml` or via `--claude.*=` flags:

| Key | Default | Meaning |
|-----|---------|---------|
| `claude.home` | `~/.claude` | Claude store location |
| `claude.excluded-projects` | _(empty)_ | Project names hidden from the UI |
| `claude.chat.engine` | `sdk` | Chat engine: `sdk` (sidecar, interactive) \| `cli` (headless) |
| `claude.chat.permission-mode` | `default` | Default mode; `default` = Allow/Deny cards on `sdk` |
| `claude.chat.allow-per-request-permission-mode` | `true` | Let the composer pick the mode per turn |
| `claude.chat.turn-timeout` … | `10m` … | 3-tier chat timeouts (inactivity / card auto-deny / hard cap) |
| `claude.pricing.models.*` | current $/MTok | Per-family rates for the estimated session cost |
| `claude.search.enabled` | `true` | Lucene index for global search (brute-force fallback) |
| `claude.push.enabled` | `true` | Web Push for WAITING turns (VAPID keys in `_claude-push`) |
| `claude.telegram.bot-token` | _(env)_ | Telegram pings for WAITING turns — seed via `CLAUDE_TELEGRAM_BOT_TOKEN` + `chat-id`, or set it in-app (✈ button); overrides persist in `_claude-telegram` |
| `claude.server-schedule.store` | `_claude-server-tasks` | In-process scheduler's task store |
| `claude.server-schedule.permission-mode` | `plan` | Default mode for scheduled runs |
| `claude.tasks-root` | `_claude-tasks` | OS-scheduler artifacts (relative to the server dir) |
| `claude.auto-open-browser` | `true` | Open the browser on startup |
| `claude.os-integration` | `auto` | `auto` \| `windows` \| `mac` \| `linux` \| `noop` |
| `server.address` | `0.0.0.0` | Bind address (prod LAN; `dev` profile uses `127.0.0.1`) |
| `server.port` | `8443` | HTTPS port (`8080` plain http in the `dev` profile) |
| `claude.security.password` | _(env)_ | Shared login password — set `CLAUDE_SECURITY_PASSWORD` |
| `claude.security.allowed-cidrs` | `127.0.0.1, ::1` | Client IPs allowed through — add your LAN/VPN subnets |

The full set (sidecar paths, executables, all timeouts) is documented in
[CLAUDE.md](CLAUDE.md).

## Network access & security

The server reads your filesystem and launches processes, so it is **not** wide open: reaching it
requires all of (1) a source IP in `claude.security.allowed-cidrs` (default localhost only — add
your LAN/VPN subnets to let other machines in), (2) the shared password, and (3) HTTPS. A handy
place for your subnets is `./config/application.yml` next to the server (git-ignored, loaded
automatically by Spring Boot) — that keeps your network layout out of version control. The self-signed keystore (`certs/keystore.p12`)
is generated locally by `scripts/gen-cert.bat <lan-ip>` and is git-ignored — pass your real LAN IP
so other machines don't hit a certificate host-mismatch. With security on, a blank password is a
hard startup error. To run the old loopback-only / no-login mode, set `claude.security.enabled=false`
and `server.address=127.0.0.1`.

To make a client browser fully **trust** the certificate (needed for Web Push; also removes the
warning page), download `https://<server>:8443/cert/install-cert.bat` and run it (one Windows
confirmation, then restart the browser), or grab the bare `cert/claudeweb.cer` and import it into
the trusted root store by hand. These endpoints serve only the public certificate — the same bytes
every TLS handshake sends — and work before login, but only from allowlisted IPs.

**Be deliberate with permission modes.** A chat turn or scheduled run with `acceptEdits` or
`bypassPermissions` lets an authenticated browser perform arbitrary file and shell operations on the
host — the login wall and IP allowlist are the only things in front of that. The shipped default is
the interactive `default` mode (every tool call needs your explicit Allow), and
`claude.chat.allow-per-request-permission-mode=false` pins the server default and ignores the
client's picker.

## License

[MIT](LICENSE)
