# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**ClaudeConversationWeb** — a local web app that browses Claude Code's CLI session store
(`~/.claude`) and lets you browse, search, rename, schedule, monitor and **chat in** those
conversations. It started as the web port of an earlier WPF desktop tool (not part of this repo).
The backend reads the filesystem and launches processes, so access is
gated by three layers (see `security/`): a shared-password login wall, a client-IP allowlist, and
HTTPS with a self-signed cert. Prod binds `0.0.0.0:8443` for allowlisted LAN clients; the `dev`
profile stays on `127.0.0.1:8080` over plain http.

The `full_web_approach` branch adds **in-browser chat** with two selectable engines
(`claude.chat.engine`):

- **`sdk` (shipped default)** — a persistent **Node sidecar** per active session (`sidecar/`, built
  on `@anthropic-ai/claude-agent-sdk`) speaking NDJSON over stdio with the backend. Turns have no
  per-turn cold start; tool permissions become **interactive Allow/Deny cards** in the browser
  (`canUseTool`); `AskUserQuestion` is answered **within the same turn**. The SDK drives the system
  `claude` CLI underneath, so the canonical `.jsonl` keeps being written exactly as before.
- **`cli` (legacy fallback)** — a fresh `claude -p … --output-format stream-json` process per turn,
  fully headless (permissions frozen per turn, questions end the turn).

Either way the CLI writes the canonical `.jsonl` itself, so the browse views just reload from disk
afterwards.

- **Backend:** Spring Boot 4 (Java 21), Gradle (Kotlin DSL). Uses **Jackson 3** (`tools.jackson.*`,
  not `com.fasterxml.jackson.databind`; annotations stay `com.fasterxml.jackson.annotation`).
- **Frontend:** Vue 3 + Vite + TypeScript + Pinia, in `frontend/`. `markdown-it` for rendering.
- **Sidecar:** TypeScript in `sidecar/`, bundled by esbuild to a single `dist/sidecar.mjs`.

## Build & run

- **Dev:** run the backend with the `dev` profile (`SPRING_PROFILES_ACTIVE=dev`,
  `CLAUDE_SECURITY_PASSWORD=dev`, `./gradlew bootRun` — plain http on :8080, IP allowlist off) and,
  in another shell, `cd frontend && npm run dev`. Vite serves the SPA on :5173 and proxies `/api` +
  `/sse` to :8080 (single origin, no CORS). `start-dev.bat` sets all of this; the login password is `dev`.
- **Packaged:** `./gradlew bootJar` builds the Vue app into `src/main/resources/static` and bakes it
  into the jar. Run `java -jar build/libs/claude-conversation-web-0.0.1-SNAPSHOT.jar` and open
  https://127.0.0.1:8443 (auto-opens on Windows/macOS/Linux unless `claude.auto-open-browser=false`).
  Needs a keystore (`scripts/gen-cert.bat <lan-ip>`) and `CLAUDE_SECURITY_PASSWORD` set —
  `start-web.bat` does both. macOS/Linux have shell equivalents: `start-web.sh`, `start-dev.sh`,
  `scripts/gen-cert.sh`.
- **Backend-only / no Node:** add `-PskipFrontend` to skip the Vue build and `-PskipSidecar` to skip
  the sidecar build (the `sdk` engine then needs an existing bundle or `claude.chat.engine=cli`).
- **Sidecar build:** `sidecarInstall`/`sidecarBuild` Gradle tasks mirror the frontend ones; the
  esbuild bundle is copied into `src/main/resources/sidecar/` (baked into the jar) and also lives at
  `sidecar/dist/sidecar.mjs`, which dev runs pick up directly (see `SidecarLocator`). Packaged runs
  extract the jar resource to `_claude-sidecar/` next to the server, overwriting on first use per run.
- JDK 21, Node 20+ required (verified: JDK 21, Node 22).

## Config (`@ConfigurationProperties("claude")`, see `application.yml`)

- `claude.home` — Claude store (default `~/.claude`).
- `claude.tasks-root` — scheduled-task artifacts, **relative to the server working dir** (default
  `_claude-tasks`, i.e. next to the project). No absolute paths for our own data.
- `claude.auto-open-browser` — open the browser on startup.
- `claude.os-integration` — `auto` | `windows` | `mac` | `linux` | `noop` (`auto` detects from `os.name`).
- `claude.chat.*` — in-browser chat: `engine` (`sdk` | `cli`; **code default `cli`, shipped yml sets
  `sdk`**; at startup `ChatEngineConfig` probes node + the sidecar bundle and **falls back to `cli`
  with a WARN** if either is missing, logging node/claude versions otherwise), `permission-mode`
  (`plan` | `acceptEdits` | `bypassPermissions` | `default` | `dontAsk` | `auto`; code default `plan`,
  shipped yml sets `default` — interactive on the sdk engine), `allow-per-request-permission-mode`
  (default `true` — the composer's mode picker), `executable` (default `claude`, resolved on PATH;
  the sdk engine resolves it to an absolute path before handing it to the sidecar), `node-executable`
  (default `node`), `sidecar-path` (blank = auto-locate). Timeouts: `turn-timeout` (`10m`; sdk =
  inactivity watchdog **paused while a card waits on the user**, cli = emitter hard cap),
  `permission-timeout` (`5m`, sdk — auto-deny an unanswered card), `session-idle-timeout` (`15m`,
  sdk — reap warm sidecars), `turn-hard-timeout` (`60m`, sdk — absolute cap per streamed turn).
  Per-turn the browser may also pick a `model` alias (`haiku|sonnet|opus|fable`, validated by
  `ChatModels`; sdk = `setModel`, cli = `--model`). Pasted/dropped composer images:
  `image-max-count` (4) + `image-max-bytes` (5MB), sdk engine only (`ChatAttachments` validates
  server-side; the cli engine 400s). See the engine gotchas below.
- `claude.pricing.*` — $/MTok per model family (substring match on the model id, first wins:
  haiku/sonnet/opus/fable) + `cache-write-multiplier` (1.25) / `cache-read-multiplier` (0.10), used
  for the **estimated** per-session cost computed from `message.usage` (`service/Pricing`,
  aggregated in `SessionInfo.addUsage`, shown via `getUsageLine`/`getUsageTooltip`).
- `claude.search.*` — Lucene full-text index for global search: `enabled` (default `true`),
  `index-dir` (default `_claude-search-index`, relative to the working dir). See the search gotcha.
- `claude.push.*` — web push for WAITING turns: `enabled` (default `true`), `store`
  (`_claude-push` — VAPID keypair + subscriptions), `subject`, `cooldown` (30s per-session burst
  collapse), `public-base-url` (absolute origin for Telegram deep links only).
- `claude.telegram.*` — `enabled` (default `false`), `bot-token` (via `CLAUDE_TELEGRAM_BOT_TOKEN`),
  `chat-id`; fires on the same WAITING events, no cert/service-worker requirements. These are
  startup seeds: the in-app **settings dialog** (header ✈ button, `TelegramSettingsController`
  `/api/settings/telegram` → `TelegramSettingsService`) edits them live and persists overrides to
  `store` (default `_claude-telegram/settings.json`, relative to the working dir) — a stored file
  then wins over this yml on restart. The bot token is write-only over the API (GET returns a
  `tokenSet`/`tokenFromEnv` flag, never the token), and an env/yml-provided token is **never copied
  to disk** (a blank UI token field keeps it).
- `claude.server-schedule.*` — second (in-process) scheduler: `store` (default `_claude-server-tasks`,
  relative to the server working dir — a **separate** root from `tasks-root`), `permission-mode`
  (default `plan`) + `allow-per-request-permission-mode` (default `true` — the modal's per-task picker),
  `scheduler-pool-size` (default `2`). Per task: `maxTurns` (0 = unlimited) adds `--max-turns` — a
  cheaper brake on a looping run than the hour limit. Same RCE caveat as chat (see the gotcha below).
- `claude.security.password` — shared login password (set via `CLAUDE_SECURITY_PASSWORD`). Blank →
  the app refuses to start when security is on.
- `claude.security.allowed-cidrs` — client IPs/CIDRs allowed through the IP filter (default
  `127.0.0.1`, `::1` — add your LAN/VPN subnets to let other machines in).
- `claude.security.enabled` (default `true`) / `enforce-ip-allowlist` (default `true`) / `session-ttl`
  (default `12h`). Tests set `enabled=false`; the `dev` profile sets `enforce-ip-allowlist=false`.
- `server.ssl.*` + `server.address: 0.0.0.0` / `port: 8443` — TLS keystore + LAN bind (prod
  `application.yml`); the `dev` profile (`application-dev.yml`) overrides to plain http on `127.0.0.1:8080`.

## Architecture

- `domain/` — immutable-ish DTOs (`ProjectInfo`, `SessionInfo`, `ChatMessage`, `ToolBlock`,
  `SearchHit`, `LiveSession`, `LiveSnapshot`, `LiveStatus`, `ScheduledTaskInfo`, `TaskSpec`,
  `ScheduleKind`). Display fields (`title`, `metaLine`, …) are computed server-side and serialized.
- `service/` — `ClaudeDataService` (all `.jsonl` parsing/search/rename/branch + path-traversal
  guards; parses `message.usage`+`model` into the usage aggregates and Edit/Write tool inputs into
  `ToolBlock.filePath/oldText/newText`), `Pricing` (cost estimation, plain class), `LiveStatusService`
  (registry + `ProcessHandle` PID-alive), `Format`, `live/{SseHub, LivePoller, SessionWatchService}`
  (SSE; the watcher also publishes `SessionsChanged` as an in-process event for the search indexer),
  `schedule/ScheduledTaskService`.
- `service/search/` — `SearchIndexService` (Lucene index, one doc per session file, background
  build + watcher-driven increments, wipe-and-rebuild on corruption) + `SearchService` (index
  candidates → exact per-file verify via `searchSessionFile`, brute-force fallback). The
  `SearchController` talks to `SearchService`.
- `service/notify/` — `NotificationDispatcher` (own daemon thread; dedupe per requestId + cooldown
  per session) fanning WAITING events to `PushSender`s: `WebPushSender` (`com.interaso:webpush`,
  VAPID keys + `PushSubscriptionStore` under `_claude-push/`) and `TelegramSender` (bot
  sendMessage, injectable HTTP seam; `sendForResult` surfaces the API status for the test button).
  `TelegramSettingsService` applies UI overrides onto the live `ClaudeProperties` at
  `ApplicationReadyEvent` and persists them under `_claude-telegram/`.
- `service/chat/` — the chat engines behind one interface (`ChatEngine`, selected by
  `ChatEngineConfig` from `claude.chat.engine`; shared helpers `PermissionModes` (the resolve gate)
  and `ChatEvents` (browser-event builders)):
  - `CliChatEngine` — legacy: drives `claude -p` for one turn, parses its stream-json NDJSON into
    simplified browser events, one in-flight turn per session id, kills the process at a question.
  - `chat/sdk/` — `SdkChatEngine` (per-session `SidecarSession` map, idle reaper, `decide`/`answer`
    routing), `SidecarSession` (one Node sidecar process: protocol I/O, pending-ask map, the 3-tier
    timeouts), `SidecarProtocol` (Spring→sidecar line builders — **mirror of
    `sidecar/src/protocol.ts`, keep in sync**), `SidecarLocator` (explicit path → `sidecar/dist` in
    dev → extract from jar).
- **Two scheduling approaches, side by side (don't conflate them):**
  - *First — `schedule/ScheduledTaskService` (`/api/schedule`):* delegates to the **Windows Task
    Scheduler** via generated `.ps1`/`.vbs` (Windows-only; `os.supportsScheduling()` gates writes).
  - *Second — `schedule/server/*` (`/api/server-schedule`):* the always-on server fires runs itself.
    `ServerScheduleService` owns its **own** `ThreadPoolTaskScheduler` (created internally, NOT a
    `TaskScheduler` bean — so the app's `@Scheduled` methods keep their default executor) + `CronTrigger`
    (daily/weekly) or a one-shot (once); a due trigger only *dispatches* to `ServerTaskRunner.submit`,
    which runs `claude -p` headlessly on the chat executor (reusing `StreamingProcessRunner`), writes a
    `report-*.md` (+ `latest.md`), and persists last-run state. `ServerTaskStore` keeps each task as a
    `task.json` under a **separate** store root (`_claude-server-tasks/`). **Cross-platform** — capabilities
    are always `true`. Tasks re-arm on startup via an `ApplicationReadyEvent` listener.
- `os/` — `OsIntegration` (interface) + `WindowsOsIntegration`, `MacOsIntegration` /
  `LinuxOsIntegration` (sharing `UnixOsIntegration`), and `NoopOsIntegration`, chosen by `OsConfig`
  from `claude.os-integration` + `os.name`; `ProcessRunner` wraps `ProcessBuilder` (mockable),
  `StreamingProcessRunner` launches a process and streams stdout line-by-line (chat turns). All
  OS-specific launch/reveal behaviour lives here so the rest is cross-platform/testable. The
  *first* scheduling approach (`ScheduledTaskService`) is the one still-Windows-only piece
  (`supportsScheduling()`); the *second* (`schedule/server/*`) is in-process and cross-platform.
- `sidecar/` — the Node sidecar (TypeScript): `src/main.ts` (stdin router + `query()` bridge with
  `canUseTool` → browser cards), `src/protocol.ts` (typed protocol), esbuild bundle, vitest tests
  with an injected fake `query`.
- `config/` — `ClaudeProperties` (`@ConfigurationProperties("claude")`, incl. nested `Chat`),
  `AsyncConfig` (`claudeChatExecutor` thread pool for chat-turn reader threads +
  `claudeChatScheduler` timer pool for the sdk engine's watchdogs), `ChatEngineConfig`, `WebConfig`
  (asset cache headers), `StartupBrowserOpener`.
- `web/` — REST controllers + `LiveStreamController` (SSE) + `ChatController` (NDJSON chat streaming
  + `POST …/permission` + `POST …/answer` for the sdk engine's in-flight cards) +
  `ChatCapabilitiesController` (`GET /api/chat/capabilities` — the composer gates the `default` mode
  and the image-attach UI on it) + `SessionController` (incl. `POST …/branch` — fork at a message) +
  `PushController` (`/api/push/key|subscribe|unsubscribe`) + `TelegramSettingsController`
  (`/api/settings/telegram` GET/POST + `…/test`) + `CertificateController` (`/cert/**` —
  cert download + Windows trust installer, pre-login) + `ApiExceptionHandler` + `SpaController`
  + `AuthController` (login/logout/status).
- `security/` — `SecurityProperties`, `CidrMatcher` (IP/CIDR match, IPv4-mapped-v6 aware),
  `SessionStore` (in-memory tokens), `IpAllowlistFilter` + `AuthFilter` (`@Order`ed servlet filters),
  `SessionCookie`, `SecurityConfig` (builds the matcher; fail-fast if no password).
- `frontend/src/` — `stores/` (Pinia; `conversation.ts` owns chat `send`/`cancel`/`decidePermission`/
  `answerQuestion`/`syncPendingAsks`/`branchFrom` + optimistic bubbles incl. live thinking,
  tool-result bodies and image-attachment chips), `components/` (3-pane UI; `Composer.vue` is the
  chat box with the permission-mode and model pickers + image paste/drop chips;
  `PermissionView.vue`/`QuestionView.vue` are the interactive cards — Edit/Write permissions render
  a `DiffView.vue` diff before Allow/Deny; `ToolBlockView.vue` delegates to `DiffView` when a block
  carries `oldText`/`newText`; `MessageCard.vue` has the hover ⑂ fork button; `AppHeader.vue` has
  the notification bell + the 📲 web-push toggle + the ✈ Telegram-settings button opening
  `TelegramSettingsDialog.vue`), `api/` (REST + SSE + NDJSON chat clients),
  `lib/markdown.ts`, `lib/diff.ts` (pure jsdiff row model), `lib/notify.ts` (in-tab Notifications +
  web-push subscribe with graceful 'unavailable' on untrusted origins), `lib/deepLink.ts`
  (`#/p/<pid>/s/<sid>` + SW messages), `theme.css`. `frontend/public/sw.js` is the service worker
  (push + notificationclick).

## Data model (read from `~/.claude`)

- Projects = `~/.claude/projects/<encoded-path>/`; real cwd comes from the `cwd` field in sessions
  (folder-name decode is fallback only).
- Sessions = `<sessionId>.jsonl`; line `type`s include `user`, `assistant`, `ai-title`,
  `custom-title`, `agent-name`. Title priority: `custom-title` > `ai-title` > first user prompt.
- Live status = `~/.claude/sessions/<pid>.json` (`busy`→WORKING / `idle`→IDLE); trusted only if the
  PID is alive. WAITING never comes from the registry (see the WAITING/IDLE gotcha).
- Chat doesn't own a store of its own: a turn `--resume`s an existing session (or `--session-id`s a
  new UUID), the CLI appends to the canonical `.jsonl`, and the browse views reload from disk. The
  optimistic user/assistant bubbles are replaced by canonical disk content when the turn completes.

## Gotchas (don't regress)

- **Jackson 3.** `JsonNode`/`ObjectMapper` are `tools.jackson.databind.*`. Exceptions are unchecked.
- **PowerShell output.** `ProcessRunner.run` captures stdout SEPARATELY from stderr — `Get-ScheduledTask`
  writes noise to stderr that, if merged, corrupts the JSON parse in `enrichState`.
- **Scheduled-task encoding.** `run.ps1` forces UTF-8 console/output, reads the prompt as UTF-8,
  strips a BOM, and passes it positionally (`$null | & claude -p $prompt`). `-EncodedCommand` is
  base64 of UTF-16LE. Scripts live as templates in `resources/scheduler/` (keeps backslashes out of
  Java literals).
- **Server-schedule = the same RCE surface as chat.** A server-scheduled run is just `claude -p`
  fired on a timer; headless can't prompt, so the task's `permission-mode` is final per run and
  `acceptEdits`/`bypassPermissions` let an authenticated browser do host work unattended. The mode is
  validated + frozen at create (`ServerScheduleService.resolveMode`, unit-tested); default `plan`. Don't
  expose the in-process scheduler as a `TaskScheduler` bean — it would hijack the app's `@Scheduled`
  methods (`SessionStore`, `LivePoller`) onto its pool; it owns a private `ThreadPoolTaskScheduler`
  instead. The cron job must only *dispatch* (`runner.submit`) — running inline would block a scheduler
  thread for the whole turn.
- **Live merge.** SSE updates merge projects/sessions IN PLACE (`mergeFrom`) and never reset the
  selected session, scroll, or open conversation. Covered by `frontend` store tests.
- **The watcher reload yields to a turn this tab is streaming.** The CLI appends to the `.jsonl`
  MID-turn, so the file-change reload of the open conversation (`live.onSessionsChanged`) would
  replace the streamed view while it's live — detaching the optimistic bubble (later deltas/cards
  render into a dead object) and showing AskUserQuestion as a raw tool block instead of the card.
  It's skipped while `conversation.isStreaming(sid)`; the canonical reload happens at turn end in
  `runTurn`. Additionally `conversation.reload` carries UNDECIDED permission/question cards over
  its wholesale replace (the canonical `.jsonl` never contains cards; a cli-engine question has no
  server-side pending copy to re-fetch). Both covered by `frontend` store tests.
- **CLI launch writes a launcher `.cmd`.** `WindowsOsIntegration` writes the claude command into a
  generated `.cmd` and opens it via `cmd /c start "" cmd /k "<launcher>"`. Bare
  ProcessBuilder/CreateProcess shows no window and can't run the `wt.exe` app-execution alias
  (needs ShellExecute); and keeping the command in a FILE avoids shell re-parsing leaking stray
  tokens (e.g. a literal `cmd`) into claude's input. Note `File.isFile()` returns **false** on the
  `WindowsApps\wt.exe` alias (it's a reparse point), so don't gate launching on it.
- **Unix CLI launch mirrors that file-launcher trick.** `UnixOsIntegration` writes the claude command
  into a generated executable script (`.command` on macOS so Terminal runs it, `.sh` on Linux) ending
  in `exec "$SHELL"` (the analogue of `cmd /k` — keeps the window open). macOS opens it with
  `open -a Terminal <launcher>` (no AppleScript/Automation prompt). Linux has **no standard terminal**:
  `LinuxOsIntegration` probes a list (honoring `$TERMINAL` / `x-terminal-emulator` first) and passes the
  single launcher token, sidestepping the `-e "<string>"` vs `-e <argv>` split between emulators — if
  none is found the action 501s. Linux "reveal" uses the freedesktop `FileManager1.ShowItems` D-Bus
  call, falling back to `xdg-open` on the parent folder when `dbus-send` is absent. The `PATH`/terminal
  probe is injected (`Predicate<String> onPath`) so the tests stay deterministic off-platform.
- **The permission mode is resolved server-side — and `bypassPermissions` is RCE.** Both engines run
  the mode through `PermissionModes.resolve` (unit-tested; keep it deterministic). With
  `bypassPermissions`/`acceptEdits` an authenticated browser runs arbitrary shell/file ops on the
  host; the ONLY things in front of that are the login wall + IP allowlist.
  `allow-per-request-permission-mode=false` forces the server default and ignores the client's
  picker. On the **sdk** engine `default` is the interactive gate (browser Allow/Deny cards,
  auto-deny after `permission-timeout`); with `bypassPermissions` the SDK skips `canUseTool`,
  so no cards appear. On the **cli** engine `default` can't prompt (headless denies tools) — use
  plan/acceptEdits/bypassPermissions there.
- **`bypassPermissions` is a LAUNCH capability, not a switchable mode (sdk engine).** The CLI only
  bypasses when launched with `--dangerously-skip-permissions`; `setPermissionMode` INTO bypass on a
  live process is refused by the SDK, and OUT of it a flagged process can't be trusted to start
  prompting again. `SidecarSession.ensureProcess` therefore **relaunches the sidecar** whenever a
  turn's mode crosses the bypass boundary (the fresh process resumes the session from disk; tested
  in `SidecarSessionTest`). Mode changes within the non-bypass family still ride `user_turn` →
  `setPermissionMode` on the warm process. If a `set*` call still fails, the sidecar fails just that
  turn (`turn_error`), never `fatal` — a refused switch is not recorded, so the next turn retries it.
- **Spring↔sidecar protocol lives in TWO files — keep them in sync.** `SidecarProtocol.java`
  (builders + `SidecarProtocolTest` pinning field names) and `sidecar/src/protocol.ts` (types used by
  `main.ts`). A rename on one side without rebuilding/updating the other silently breaks chat. The
  sidecar has NO timers of its own — Java (`SidecarSession`) owns the watchdog/auto-deny/idle-reap;
  the sidecar just bridges `canUseTool` to pending promises (denied on `interrupt`).
- **The sidecar's `ready.sessionId` is authoritative.** Java pre-generates a UUID for new sessions
  (passed via `extraArgs {"session-id": …}`), but re-keys its session map to whatever the SDK's
  `system:init` reports (`SdkChatEngine.rekey`), and only then sends the browser `session` event.
  Sidecar restarts after the first ready always `resume` — the session exists on disk by then.
- **Sidecar SDK options are deliberate CLI-parity choices.** `pathToClaudeCodeExecutable` = the
  system `claude` (versions match terminal sessions — the SDK's bundled CLI is never used),
  `settingSources: ['user','project','local']` + `systemPrompt: {preset: 'claude_code'}` (the SDK
  default loads NO CLAUDE.md/settings — without these the chat would behave unlike the terminal),
  `includePartialMessages` (text deltas). Pin the `@anthropic-ai/claude-agent-sdk` version in
  `sidecar/package.json`; `engine: cli` is the escape hatch for SDK/CLI drift.
- **Chat prompt goes via stdin, not argv.** `StreamingProcessRunner` writes prompts/protocol lines to
  the child's stdin as UTF-8 (the cli engine write-once-then-close; the sdk engine keeps stdin open
  via `startInteractive`/`writeLine` — synchronized, flushed per line). Passing text as a
  command-line arg would let the Windows argv codepage mangle Cyrillic. stdout (the NDJSON event
  stream) and stderr are read on SEPARATE threads so a stray stderr line never corrupts the parse.
- **`stream-json` needs `--verbose` + `--include-partial-messages` (cli engine).** In print mode the
  CLI only emits the streaming events (`content_block_delta` text deltas, `content_block_start`
  tool_use) with both flags. `CliChatEngine` translates those into the simplified
  `session`/`text-delta`/`tool`/`question`/`done`/`error` events the browser consumes; the sdk
  engine emits the same vocabulary plus `permission`, and its `question` carries a `requestId`.
- **`AskUserQuestion` is surfaced as an interactive question, not a tool block — per engine:**
  - *sdk:* the SDK routes the tool through `canUseTool`; the sidecar emits a `question` message
    (suppressing the tool block), the browser card's answer goes back via `POST …/answer` →
    `question_answer` → `canUseTool` resolves with `updatedInput {questions, answers}` and the model
    continues **the same turn**. The post-turn canonical reload is correct here (the `.jsonl` has the
    real tool_use/tool_result), so `conversation.ts` does NOT set `endedAtQuestion` when the event
    has a `requestId`.
  - *cli (legacy):* headless `claude -p` can't pause — the CLI auto-errors the tool and the model
    would continue the SAME turn with a fallback answer. So `CliChatEngine.endAtQuestion` **ends the
    turn at the question** (sends `done`, kills the process tree; the non-zero exit is expected —
    `finish` skips the error when `RunningTurn.endedAtQuestion`). The browser keeps the card, **skips
    the canonical reload for that turn**, and `answerQuestion` sends the pick as the **next turn**
    (`--resume`). This branch is keyed on the question event having NO `requestId` — don't remove it
    while the cli engine exists.
  - *canonical render:* in history loaded from disk the `AskUserQuestion` tool_use body is formatted
    as readable question/options(/picked answer) text (`ClaudeDataService.questionBody`), not the
    raw input JSON blob.
- **sdk timeouts are 3-tier — don't collapse them.** `turn-timeout` is an *inactivity* watchdog that
  MUST pause while a permission/question card is pending (a human thinking is not a hung turn —
  tested in `SidecarSessionTest`); `permission-timeout` auto-denies an unanswered card so the turn
  continues deterministically; `turn-hard-timeout` is the `ResponseBodyEmitter` cap. The idle reaper
  (`session-idle-timeout`) only closes sidecars with no turn AND no pending ask.
- **The turn slot is the SIDECAR's busy state, not the emitter's.** `SidecarSession.sidecarBusy` is
  true from `user_turn` until the sidecar reports `turn_done`/`turn_error` (or dies / a timeout gives
  up). A closed browser tab completes the emitter but must NOT free the slot — that would drop the
  live badge and let a second turn collide with the running one in the same SDK session (tested:
  `turnStaysBusyAfterTheBrowserStreamDies`).
- **Pending cards survive the tab.** `SidecarSession` keeps each unanswered ask's browser-shaped
  card; `GET …/sessions/{sid}/pending` returns them, and a session whose turn is parked on a card is
  reported **WAITING** (orange badge) instead of WORKING. The frontend re-attaches the cards via
  `conversation.syncPendingAsks` — called after `load`/`reload` and from `live.applySnapshot` when
  the open session turns WAITING — so a reloaded page or second tab can answer a card it never saw
  streamed. It self-throttles: no refetch while an undecided card is on screen, and none while this
  tab's streamed bubble is still ATTACHED to the view (cards arrive on it). "This tab streams the
  turn" alone is not enough to skip — a `load()` may have detached the bubble, and a queued turn
  fired while the user was on another conversation never had one; then the fetch is the only way
  the card reaches the screen.
- **WAITING means "needs your decision", IDLE means "terminal open".** Registry `idle` maps to the
  neutral grey `IDLE` (an open CLI shell waits for nothing specific); the orange `WAITING` badge and
  `waitingCount` are reserved for chat turns parked on a permission/question card. Merge precedence
  everywhere (LivePoller, the frontend project aggregation): WAITING > WORKING > IDLE. Don't map
  registry idle back to WAITING — that false urgency is exactly what this split removed.
- **Always-allow persists the SDK's `suggestions`, not anything we invent.** The sidecar passes
  `canUseTool`'s `suggestions` (PermissionUpdate[]) through the `permission` card; "Always allow"
  sends `always:true` back and the sidecar resolves with `updatedPermissions = suggestions` —
  the SDK then writes the rule (e.g. session-scoped `setMode`/allow rules) itself. The button only
  renders when suggestions are non-empty; auto-deny never persists rules.
- **A sidecar that dies before its first `ready` is replayed once.** Transient spawn failures (the
  SDK mislabels ANY launch failure as "binary not found") would otherwise kill the first turn after
  a server start. `onProcessExit` restarts the process and resends the turn a single time; the
  executable is also pre-resolved to an absolute path (`SidecarLocator.resolveExecutable`).
- **Streaming endpoints are excluded from gzip.** `server.compression.mime-types` (in `application.yml`)
  deliberately omits `application/x-ndjson` (chat) and `text/event-stream` (`/sse/live`) so they stream
  unbuffered. The chat executor (`AsyncConfig`) uses `queueCapacity=0` (SynchronousQueue): reader tasks
  block for the whole turn, so queueing would serialize concurrent turns.
- **The perimeter is the security layer, not loopback.** Prod binds `0.0.0.0:8443`; the gate is the
  IP allowlist + shared-password login wall + TLS (`security/`). Filter order: `IpAllowlistFilter`
  (403) → `AuthFilter` (401 for `/api`+`/sse`; static/SPA always pass so the login screen can load).
  The session cookie is `Secure` only over https (`req.isSecure()`), so dev stays on http without
  dropping it. A blank password with security on is a hard startup failure. The self-signed keystore
  (`certs/keystore.p12`) is git-ignored — generate per machine via `scripts/gen-cert.bat <lan-ip>`.
- **Indexed search matches words/prefixes; the verify pass keeps results exact.** The Lucene path
  (`SearchService`) ANDs analyzed terms (last one a prefix) — `pars` finds "parser", `rser` no
  longer does. Every candidate is re-scanned with `ClaudeDataService.searchSessionFile`, so
  count/snippet/precision are byte-identical to brute force; the brute-force scan remains the
  fallback (index disabled / not ready / unparseable query → `index.search` returns **null**, not
  empty). Tests run with `claude.search.enabled=false` so the index never writes into the project
  dir or races fixtures — keep that property when adding `@SpringBootTest`s.
- **Branching extends the cut over dangling tool_use.** `branchSession` copies up to AND INCLUDING
  the chosen line; if that line is an assistant message with `tool_use` blocks, the immediately
  following user `tool_result` lines are pulled in too — a fork ending on an unanswered tool call
  breaks `--resume`. SessionIds are rewritten per line; the fork title is `⑂ <original>` via the
  rename plumbing. The fork button only renders for messages with a `uuid` (optimistic bubbles
  have none — they're not on disk yet).
- **The push dispatcher must never run on the sidecar reader thread.** `SidecarSession.registerPending`
  fires the `WaitingListener` synchronously on the stdout reader thread; `NotificationDispatcher`
  therefore only does map bookkeeping there and delivers (HTTP to push services / Telegram) on its
  own single daemon thread — NOT on `claudeChatScheduler` (a small timer pool whose delay would
  stall watchdog/auto-deny). One card (`requestId`) pushes at most once; per-session `cooldown`
  collapses bursts. Web push needs the self-signed cert TRUSTED on the client (SW registration
  fails otherwise — `togglePush` reports `unavailable`, the 📲 button opens `CertHelpDialog`, and
  the in-tab Notification path keeps working); Telegram has no such requirement.
- **The server distributes its own certificate — public material, pre-login by design.**
  `GET /cert/claudeweb.cer` (DER) and `GET /cert/install-cert.bat` (self-contained
  `certutil -addstore -user Root` installer with the PEM embedded, CRLF endings) are served by
  `CertificateController` from `security/ServerCertificate` (reads the keystore configured in
  `server.ssl.*`; 404 when none — the http dev profile). `/cert/**` deliberately sits OUTSIDE
  `/api` so the login wall doesn't gate it (a fresh client trusts the cert before logging in) while
  the IP allowlist still applies. A TLS handshake sends these bytes to every client anyway — never
  serve anything from the keystore but the certificate. Tests use the committed junk-key fixture
  `src/test/resources/certs/test-keystore.p12` (gitignore-negated past `certs/` + `*.p12`).
- **Composer images are sdk-only and validated server-side.** The browser gates the UI on
  `capabilities.images`, but `ChatAttachments.validate` (count/size/media-type whitelist
  png|jpeg|gif|webp/base64 shape) is the actual gate; `CliChatEngine` 400s before spawning. The
  sidecar builds text+image content blocks only when images are present — a text-only `user_turn`
  keeps the plain-string `content` (wire behavior identical to pre-image builds). `user_turn.images`
  lives in BOTH protocol files — same keep-in-sync rule as everything else.
- **Edit/Write diffs preserve indentation and keep `body` populated.** `capDiffText` (20k cap)
  deliberately does NOT strip — leading whitespace is part of a diff; and the generic pretty-JSON
  `body` stays filled because the in-conversation filter searches it.

## Tests

- `./gradlew test` — JUnit 5 unit tests (parsing/search/rename/cwd-decode/tool reclassification,
  scheduler script generation, per-OS command building, IP/CIDR matching, and the chat contracts —
  `CliChatEngineTest` asserts `buildCommand` flags + `resolveMode` security behaviour;
  `SidecarProtocolTest` pins the Spring→sidecar wire shapes; `SidecarSessionTest` covers event
  translation, decision routing, auto-deny, and the watchdog pause (against a `TestProcesses` fake);
  `StreamingProcessRunnerTest` exercises interactive stdin against a real child JVM; the second
  scheduler's `ServerScheduleServiceTest` + `ServerTaskRunnerTest` cover its CLI contract) +
  `@SpringBootTest`+MockMvc integration tests against a temp `claude.home` fixture, including the
  login wall (401 without a cookie, 200 with) and OS-capability reporting. No Docker.
  `SecurityIntegrationTest` turns the wall on; other tests run with it off via
  `src/test/resources/application.properties` (`claude.security.enabled=false`, plus
  `claude.search.enabled=false`). Newer suites: `PricingTest`/`FormatTest` (cost estimation),
  `ChatAttachmentsTest` (image validation), `NotificationDispatcherTest`/`TelegramSenderTest`
  (push fan-out with fakes), `TelegramSettingsServiceTest` (UI-override persistence + env-token
  precedence + send-test), `SearchIndexServiceTest`/`SearchServiceTest` (index build/increment/
  corruption + fallback), and `BranchIntegrationTest`/`PushIntegrationTest`/`SearchIndexIntegrationTest`
  (own temp stores — branching writes session files, push generates keys).
- `cd frontend && npm run test` — Vitest store tests (live merge, queue, cli questions, sdk
  permissions/in-turn questions in `conversation.permission.test.ts`, fork in
  `conversation.branch.test.ts`, image attachments in `conversation.attachments.test.ts`, the diff
  row model in `lib/__tests__/diff.test.ts`).
- `cd sidecar && npm test` — Vitest for the stdin router + `canUseTool` bridge (fake `query`).

## Conventions

- Match the surrounding code. New jsonl-derived data → extend `ClaudeDataService` + a domain DTO.
  New user action → controller + `OsIntegration`/service + a Pinia store + component. New colours →
  `theme.css` CSS variables (clay accent `#D97757`).
