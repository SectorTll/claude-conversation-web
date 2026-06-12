// Mirrors the backend JSON (computed fields are serialized server-side).

/**
 * WORKING = busy; IDLE = a live but idle CLI shell (terminal open, nothing needed); WAITING = a
 * chat turn parked on a permission/question card — genuinely needs the user's decision.
 */
export type LiveStatus = 'NONE' | 'WORKING' | 'IDLE' | 'WAITING'

export interface ProjectInfo {
  projectId: string
  folderName: string
  realPath: string
  sessionCount: number
  lastActivity: string
  liveStatus: LiveStatus
  shortName: string
  lastActivityDisplay: string
  sessionCountDisplay: string
  showStatus: boolean
  waiting: boolean
  working: boolean
  idle: boolean
}

export interface SessionInfo {
  sessionId: string
  customTitle: string | null
  aiTitle: string | null
  firstPrompt: string | null
  cwd: string | null
  gitBranch: string | null
  version: string | null
  userMessages: number
  assistantMessages: number
  lastActivity: string
  started: string | null
  liveStatus: LiveStatus
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
  costUsd: number
  title: string
  hasCustomTitle: boolean
  preview: string
  lastActivityDisplay: string
  totalMessages: number
  metaLine: string
  shortId: string
  showStatus: boolean
  waiting: boolean
  working: boolean
  idle: boolean
  statusText: string
  // Compact usage summary ("12.3k in · 45.6k out · $0.42") and full-number tooltip; null when no usage.
  usageLine: string | null
  usageTooltip: string | null
}

export interface ToolBlock {
  kind: 'use' | 'result'
  title: string
  body: string
  // Edit/Write tool calls: structured change payload for the diff renderer. oldText is null for
  // Write (pure addition). Absent on every other tool.
  filePath?: string | null
  oldText?: string | null
  newText?: string | null
  // Client-only: set on live-streamed blocks so a later tool-result event can fill in the body.
  id?: string
}

/** One selectable answer of an AskUserQuestion question. */
export interface QuestionOption {
  label: string
  description: string
}

/** One question of an AskUserQuestion tool call (a single call may carry several). */
export interface QuestionSpec {
  question: string
  header: string
  multiSelect: boolean
  options: QuestionOption[]
}

/**
 * An AskUserQuestion attached to an assistant bubble during a live turn (client-only — not part of
 * the backend ChatMessage). `answered` locks the choices once the user has replied. `requestId` is
 * present on the sdk engine, where the answer goes back INTO the same turn; without it (cli engine)
 * the turn ended at the question and the answer is sent as the next turn.
 */
export interface PendingQuestion {
  questions: QuestionSpec[]
  answered: boolean
  requestId?: string
}

/**
 * A tool-permission request attached to an assistant bubble during a live turn (client-only, sdk
 * engine). The turn is paused inside the server until the user decides (or the server auto-denies
 * on timeout). `decided`/`allowed` lock and label the card once acted on. `suggestions` are the
 * SDK's "don't ask again" rule suggestions — non-empty enables the Always-allow button.
 */
export interface PendingPermission {
  requestId: string
  toolName: string
  input: unknown
  suggestions?: unknown[]
  decided: boolean
  allowed?: boolean
}

export interface ChatMessage {
  role: string
  // Source jsonl line uuid — the cut point for "fork from here". Absent on optimistic live bubbles.
  uuid?: string | null
  timestamp: string | null
  text: string
  thinking: string | null
  tools: ToolBlock[]
  user: boolean
  assistant: boolean
  tool: boolean
  system: boolean
  hasText: boolean
  hasThinking: boolean
  hasTools: boolean
  roleHeader: string
  timeDisplay: string
  // Assistant lines only: model id + per-message usage tooltip (server-computed); absent elsewhere.
  model?: string | null
  tokensDisplay?: string | null
  // Client-only: an interactive AskUserQuestion shown on an assistant bubble mid-turn.
  question?: PendingQuestion | null
  // Client-only: interactive tool-permission cards shown on an assistant bubble mid-turn (sdk engine).
  permissions?: PendingPermission[]
  // Client-only: image thumbnails on the optimistic user bubble (the canonical reload shows the
  // server-side "[image]" placeholder instead).
  attachments?: { mediaType: string; dataUrl: string }[]
}

export interface SearchHit {
  session: SessionInfo
  projectId: string
  projectName: string
  snippet: string
  count: number
  title: string
  metaLine: string
}

export interface LiveSession {
  sessionId: string
  cwd: string | null
  status: LiveStatus
}

export interface LiveSnapshot {
  sessions: LiveSession[]
  waitingCount: number
}

export type ScheduleKind = 'ONCE' | 'DAILY' | 'WEEKLY'

export interface ScheduledTaskInfo {
  name: string
  folder: string
  prompt: string
  workingDir: string
  scheduleText: string
  taskPath: string
  state: string
  nextRun: string
  fullTaskName: string
  promptPreview: string
  subLine: string
}

export interface TaskSpec {
  name: string
  prompt: string
  workingDir: string
  allowedTools: string
  kind: ScheduleKind
  at: string // ISO local date-time, e.g. "2026-06-09T09:00:00"
  days: string[] // DayOfWeek names, e.g. ["MONDAY","FRIDAY"]
  timeLimitHours: number
  hidden: boolean
  openReport: boolean
}

// --- second approach: in-process (server-driven) scheduling ---

/** Input for creating a server-scheduled task (the always-on server fires it itself). */
export interface ServerTaskSpec {
  name: string
  prompt: string
  workingDir: string
  allowedTools: string
  permissionMode: string // '' = server default; otherwise plan|acceptEdits|bypassPermissions|default|dontAsk|auto
  kind: ScheduleKind
  at: string // ISO local date-time
  days: string[] // DayOfWeek names (Weekly only)
  timeLimitHours: number
  maxTurns: number // cap on agentic turns per run (--max-turns); 0 = unlimited
  enabled: boolean
}

/** A persisted server-scheduled task with its computed display + last-run state. */
export interface ServerScheduledTask {
  name: string
  folder: string
  prompt: string
  workingDir: string
  allowedTools: string
  permissionMode: string
  kind: ScheduleKind
  at: string
  days: string[]
  timeLimitHours: number
  maxTurns: number
  enabled: boolean
  scheduleText: string
  state: string // idle | running | disabled
  lastStatus: string // ok | failed | timeout | ''
  lastRun: string
  lastExitCode: number
  nextRun: string
  promptPreview: string
  subLine: string
}
