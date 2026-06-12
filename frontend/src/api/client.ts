import type {
  ChatMessage,
  LiveSnapshot,
  ProjectInfo,
  QuestionSpec,
  ScheduledTaskInfo,
  SearchHit,
  ServerScheduledTask,
  ServerTaskSpec,
  SessionInfo,
  SlashCommandInfo,
  TaskSpec,
} from '@/types/models'

const enc = encodeURIComponent

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

async function parseError(res: Response): Promise<never> {
  if (res.status === 401) {
    // Any 401 (e.g. an expired session) sends the app back to the login screen. Lazy import so the
    // client module doesn't statically depend on the store (which imports the client).
    const { useAuthStore } = await import('@/stores/auth')
    useAuthStore().markLoggedOut()
  }
  let message = `${res.status} ${res.statusText}`
  try {
    const body = await res.json()
    if (body && typeof body.message === 'string') {
      message = body.message
    }
  } catch {
    // non-JSON error body — keep the status line
  }
  throw new ApiError(res.status, message)
}

async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(url, { signal })
  if (!res.ok) {
    return parseError(res)
  }
  return res.json() as Promise<T>
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    return parseError(res)
  }
  return res.json() as Promise<T>
}

async function postNoBody(url: string, body: unknown): Promise<void> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    await parseError(res)
  }
}

async function del(url: string): Promise<void> {
  const res = await fetch(url, { method: 'DELETE' })
  if (!res.ok) {
    await parseError(res)
  }
}

// --- streaming chat ----------------------------------------------------------

/**
 * One simplified event from a chat turn (the backend translates the CLI/SDK stream into these).
 * `question.requestId`, `permission`, `thinking-delta` and `tool-result` only occur on the sdk
 * engine, where the turn pauses for the user instead of ending.
 */
export type StreamEvent =
  | { type: 'session'; sessionId: string }
  | { type: 'text-delta'; text: string }
  | { type: 'thinking-delta'; text: string }
  | { type: 'tool'; title: string; toolUseId?: string }
  | { type: 'tool-result'; toolUseId: string; body: string }
  | { type: 'question'; questions: QuestionSpec[]; requestId?: string }
  | { type: 'permission'; requestId: string; toolName: string; input: unknown; suggestions?: unknown[] }
  | { type: 'done' }
  | { type: 'error'; message: string }

/** A pending interactive card of an in-flight turn, as returned by GET …/pending. */
export type PendingAsk = Extract<StreamEvent, { type: 'permission' } | { type: 'question' }>

/** One session parked on unanswered cards — the global "what needs me" panel (GET /api/waiting). */
export interface WaitingItem {
  projectId: string
  sessionId: string
  title: string
  cards: PendingAsk[]
}

/** What the active chat backend can do — drives the composer's mode picker and attach UI. */
export interface ChatCapabilities {
  engine: 'sdk' | 'cli'
  defaultPermissionMode: string
  allowPerRequestPermissionMode: boolean
  // Pasted images (sdk engine only) + the server's limits, mirrored client-side for early errors.
  images?: boolean
  imageMaxCount?: number
  imageMaxBytes?: number
}

/** Telegram notification settings as the browser sees them — never the token, only whether one is set. */
export interface TelegramSettings {
  enabled: boolean
  chatId: string
  publicBaseUrl: string
  tokenSet: boolean
  // A token is set but came from the env var / yml, not the UI (so it's not editable here, only overridable).
  tokenFromEnv: boolean
}

/** Settings write: `botToken` is sent only when the user typed a new one; `clearToken` drops it. */
export interface TelegramUpdate {
  enabled: boolean
  chatId: string
  publicBaseUrl: string
  botToken?: string
  clearToken?: boolean
}

/** One pasted image on the wire: whitelisted media type + bare base64 (no data: prefix). */
export interface ChatAttachment {
  mediaType: string
  data: string
}

export interface ChatBody {
  text: string
  permissionMode?: string
  model?: string // haiku | sonnet | opus | fable | '' (the user's default)
  attachments?: ChatAttachment[]
}

/**
 * POST a chat message and consume the NDJSON reply stream, invoking {@code onEvent} per event.
 * EventSource can't POST, so we read the streamed response body via fetch + a reader. Tolerates both
 * bare-newline JSON and {@code data:}-prefixed SSE framing. 401s route through {@link parseError}.
 */
async function postStream(
  url: string,
  body: ChatBody,
  onEvent: (ev: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
  if (!res.ok || !res.body) {
    return parseError(res)
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const emit = (raw: string) => {
    const line = raw.trim()
    if (!line) return
    const json = line.startsWith('data:') ? line.slice(5).trim() : line
    if (!json || json === '[DONE]') return
    try {
      onEvent(JSON.parse(json) as StreamEvent)
    } catch {
      // partial / non-JSON keepalive line — ignore
    }
  }
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let nl: number
    while ((nl = buffer.indexOf('\n')) >= 0) {
      emit(buffer.slice(0, nl))
      buffer = buffer.slice(nl + 1)
    }
  }
  emit(buffer) // flush any trailing event without a terminating newline
}

export const api = {
  // Auth (shared password). These three are reachable without a session.
  authStatus: () => getJson<{ authenticated: boolean }>('/api/auth/status'),
  login: (password: string) => postNoBody('/api/auth/login', { password }),
  logout: () => postNoBody('/api/auth/logout', {}),

  projects: () => getJson<ProjectInfo[]>('/api/projects'),

  sessions: (projectId: string) =>
    getJson<SessionInfo[]>(`/api/projects/${enc(projectId)}/sessions`),

  session: (projectId: string, sessionId: string) =>
    getJson<SessionInfo>(`/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}`),

  messages: (projectId: string, sessionId: string) =>
    getJson<ChatMessage[]>(
      `/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}/messages`,
    ),

  search: (q: string, max = 400, signal?: AbortSignal) =>
    getJson<SearchHit[]>(`/api/search?q=${enc(q)}&max=${max}`, signal),

  rename: (projectId: string, sessionId: string, title: string) =>
    postJson<SessionInfo>(
      `/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}/rename`,
      { title },
    ),

  // Fork a session at a message: a new session containing the prefix up to that message (incl.).
  branch: (projectId: string, sessionId: string, uuid: string) =>
    postJson<SessionInfo>(
      `/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}/branch`,
      { uuid },
    ),

  // In-browser chat: stream the reply for a message to an existing session, or create a new session.
  sendMessage: (
    projectId: string,
    sessionId: string,
    body: ChatBody,
    onEvent: (ev: StreamEvent) => void,
    signal?: AbortSignal,
  ) =>
    postStream(
      `/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}/messages`,
      body,
      onEvent,
      signal,
    ),
  startSession: (
    projectId: string,
    body: ChatBody,
    onEvent: (ev: StreamEvent) => void,
    signal?: AbortSignal,
  ) => postStream(`/api/projects/${enc(projectId)}/sessions`, body, onEvent, signal),
  stopTurn: (projectId: string, sessionId: string) =>
    postNoBody(`/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}/stop`, {}),
  // sdk engine: feed an in-flight turn's pending permission/question card (the stream stays open).
  decidePermission: (
    projectId: string,
    sessionId: string,
    requestId: string,
    behavior: 'allow' | 'deny',
    message?: string,
    always = false,
  ) =>
    postNoBody(`/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}/permission`, {
      requestId,
      behavior,
      message,
      always,
    }),
  answerInTurn: (projectId: string, sessionId: string, requestId: string, answers: string[][]) =>
    postNoBody(`/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}/answer`, {
      requestId,
      answers,
    }),
  // The unanswered cards of an in-flight turn — for a reloaded page / second tab to re-render.
  pendingAsks: (projectId: string, sessionId: string) =>
    getJson<PendingAsk[]>(`/api/projects/${enc(projectId)}/sessions/${enc(sessionId)}/pending`),
  chatCapabilities: () => getJson<ChatCapabilities>('/api/chat/capabilities'),
  // Slash-command catalog for the composer autocomplete (user skills/commands + the project's).
  chatCommands: (projectId: string | null) =>
    getJson<SlashCommandInfo[]>(
      projectId ? `/api/chat/commands?projectId=${enc(projectId)}` : '/api/chat/commands',
    ),
  // Files under the chat target's cwd for @-mention autocomplete (capped server-side).
  chatFiles: (projectId: string, sessionId: string | null) =>
    getJson<{ cwd: string; files: string[]; truncated: boolean }>(
      `/api/chat/files?projectId=${enc(projectId)}` +
        (sessionId ? `&sessionId=${enc(sessionId)}` : ''),
    ),

  live: () => getJson<LiveSnapshot>('/api/live'),
  // Every session parked on an unanswered card, across all projects (the bell panel).
  waiting: () => getJson<WaitingItem[]>('/api/waiting'),

  // OS actions (wired to the backend in the OS-integration step; 501 on unsupported platforms).
  resume: (projectId: string, sessionId: string, fork: boolean) =>
    postNoBody('/api/actions/resume', { projectId, sessionId, fork }),
  newSession: (projectId: string) => postNoBody('/api/actions/new-session', { projectId }),
  openFolder: (projectId: string, sessionId?: string) =>
    postNoBody('/api/actions/open-folder', { projectId, sessionId }),
  revealFile: (projectId: string, sessionId: string) =>
    postNoBody('/api/actions/reveal-file', { projectId, sessionId }),
  resumeCommand: (projectId: string, sessionId: string, fork: boolean) =>
    getJson<{ command: string }>(
      `/api/actions/resume-command?projectId=${enc(projectId)}&sessionId=${enc(sessionId)}&fork=${fork}`,
    ),

  // Scheduling
  scheduleCapabilities: () => getJson<{ scheduling: boolean }>('/api/schedule/capabilities'),
  scheduleTasks: () => getJson<ScheduledTaskInfo[]>('/api/schedule/tasks'),
  scheduleCreate: (spec: TaskSpec) => postJson<ScheduledTaskInfo>('/api/schedule/tasks', spec),
  scheduleRun: (name: string) => postNoBody(`/api/schedule/tasks/${enc(name)}/run`, {}),
  scheduleOpen: (name: string) => postNoBody(`/api/schedule/tasks/${enc(name)}/open`, {}),
  scheduleDelete: (name: string, removeFiles = true) =>
    del(`/api/schedule/tasks/${enc(name)}?removeFiles=${removeFiles}`),

  // Server-driven scheduling (second approach)
  serverScheduleCapabilities: () =>
    getJson<{ scheduling: boolean }>('/api/server-schedule/capabilities'),
  serverScheduleTasks: () => getJson<ServerScheduledTask[]>('/api/server-schedule/tasks'),
  serverScheduleCreate: (spec: ServerTaskSpec) =>
    postJson<ServerScheduledTask>('/api/server-schedule/tasks', spec),
  serverScheduleRun: (name: string) => postNoBody(`/api/server-schedule/tasks/${enc(name)}/run`, {}),
  serverScheduleSetEnabled: (name: string, value: boolean) =>
    postNoBody(`/api/server-schedule/tasks/${enc(name)}/enabled?value=${value}`, {}),
  serverScheduleOpen: (name: string) =>
    postNoBody(`/api/server-schedule/tasks/${enc(name)}/open`, {}),
  serverScheduleReport: (name: string) =>
    getJson<{ name: string; content: string }>(`/api/server-schedule/tasks/${enc(name)}/report`),
  serverScheduleDelete: (name: string) => del(`/api/server-schedule/tasks/${enc(name)}`),

  // Telegram notification settings (the token is write-only — GET never returns it).
  telegramSettings: () => getJson<TelegramSettings>('/api/settings/telegram'),
  telegramUpdate: (body: TelegramUpdate) =>
    postJson<TelegramSettings>('/api/settings/telegram', body),
  telegramTest: () => postJson<{ ok: boolean; detail: string }>('/api/settings/telegram/test', {}),
}
