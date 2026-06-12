import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { ChatAttachment, StreamEvent } from '@/api/client'
import type { ChatMessage, PendingPermission } from '@/types/models'
import { maybeNotify } from '@/lib/notify'
import { useUiStore } from './ui'
import { useProjectsStore } from './projects'
import { useSessionsStore } from './sessions'

/** A message typed while Claude was responding, awaiting its turn. */
export interface QueuedMessage {
  text: string
  mode?: string
  model?: string
  attachments?: ChatAttachment[]
}

/** Map key for an unsaved "draft" chat (no session id until the first turn creates one). */
const DRAFT_KEY = '__draft__'

/**
 * Per-conversation turn state, keyed by session id (or DRAFT_KEY). Keeping this per-session — rather
 * than one global flag — means a turn running in one conversation never blocks or clobbers another:
 * switch to an idle neighbour and it's free to use, while the first turn finishes in the background.
 */
interface TurnState {
  busy: boolean
  queue: QueuedMessage[]
  pid: string | null // project id of the in-flight turn (for stopTurn)
  sid: string | null // server session id (for stopTurn; learned from the `session` event in draft)
}

/** Build a client-side message that satisfies the same shape MessageCard renders (serialized helpers). */
function makeMessage(role: 'user' | 'assistant', text: string): ChatMessage {
  const now = new Date()
  const hhmm = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
  return {
    role,
    timestamp: now.toISOString(),
    text,
    thinking: null,
    tools: [],
    user: role === 'user',
    assistant: role === 'assistant',
    tool: false,
    system: false,
    hasText: text.length > 0,
    hasThinking: false,
    hasTools: false,
    roleHeader: role === 'user' ? 'You' : 'Claude', // mirrors backend ChatMessage.getRoleHeader()
    timeDisplay: hhmm,
  }
}

export const useConversationStore = defineStore('conversation', () => {
  // The messages of the conversation CURRENTLY ON SCREEN (one session is viewed at a time).
  const messages = ref<ChatMessage[]>([])
  const search = ref('')
  const loading = ref(false)

  // Turn state per conversation. AbortControllers are kept out of the reactive map — no need to
  // proxy a native object, and Stop only needs to reach the right one by key.
  const turns = reactive(new Map<string, TurnState>())
  const controllers = new Map<string, AbortController>()
  // The optimistic assistant bubble each in-flight turn streams into, by key. Used to tell whether
  // the streamed view is still ON SCREEN — a load/reload that replaced `messages` detaches it, and
  // events then render into a dead object (see syncPendingAsks).
  const liveBubbles = new Map<string, ChatMessage>()

  /** The map key for the conversation currently on screen. */
  function currentKey(): string {
    const sessions = useSessionsStore()
    return sessions.draftMode ? DRAFT_KEY : (sessions.selectedId ?? '')
  }
  function turnFor(key: string): TurnState {
    if (!turns.has(key)) {
      turns.set(key, { busy: false, queue: [], pid: null, sid: null })
    }
    return turns.get(key)!
  }

  /** Busy / queue for the conversation on screen — these drive the composer and the queued bubbles. */
  const busy = computed(() => turns.get(currentKey())?.busy ?? false)
  const queue = computed<QueuedMessage[]>(() => turns.get(currentKey())?.queue ?? [])

  // In-conversation filter — text / thinking / tool title+body (port of FilterMessage).
  const filtered = computed(() => {
    const q = search.value.trim().toLowerCase()
    if (!q) {
      return messages.value
    }
    return messages.value.filter(
      (m) =>
        m.text.toLowerCase().includes(q) ||
        (m.thinking ?? '').toLowerCase().includes(q) ||
        m.tools.some(
          (t) => t.title.toLowerCase().includes(q) || t.body.toLowerCase().includes(q),
        ),
    )
  })

  async function load(projectId: string, sessionId: string) {
    loading.value = true
    try {
      messages.value = await api.messages(projectId, sessionId)
    } catch (e) {
      messages.value = []
      useUiStore().setError(e)
    } finally {
      loading.value = false
    }
    void syncPendingAsks(projectId, sessionId)
  }

  /** Silent refresh (no loading flash) for live updates to the open session. */
  async function reload(projectId: string, sessionId: string) {
    try {
      const fresh = await api.messages(projectId, sessionId)
      // The canonical .jsonl never carries interactive cards, so a wholesale replace would destroy
      // any card still waiting on the user — fatal for a cli-engine question, which has no
      // server-side pending copy to re-fetch. Carry the undecided ones over into the fresh view.
      for (const m of messages.value) {
        const undecided = (m.permissions ?? []).filter((p) => !p.decided)
        const question = m.question && !m.question.answered ? m.question : undefined
        if (!question && undecided.length === 0) {
          continue
        }
        const bubble = makeMessage('assistant', '')
        if (undecided.length > 0) {
          bubble.permissions = undecided
        }
        if (question) {
          bubble.question = question
        }
        fresh.push(bubble)
      }
      messages.value = fresh
    } catch {
      // keep the current messages on a transient error
    }
    void syncPendingAsks(projectId, sessionId)
  }

  /**
   * Re-attach the unanswered permission/question cards of an in-flight turn that THIS tab is not
   * streaming (page reloaded mid-turn, or the turn runs in another tab). Without this, a card the
   * tab never saw is unreachable and dies by auto-deny. Idempotent — already-rendered requestIds
   * are skipped — and a no-op while this tab owns the live stream (cards arrive on it).
   */
  async function syncPendingAsks(projectId: string, sessionId: string) {
    const sessions = useSessionsStore()
    if (sessions.draftMode || sessions.selectedId !== sessionId) {
      return
    }
    // While this tab's streamed bubble is still on screen, cards arrive on it — nothing to sync.
    // But "this tab streams the turn" alone is NOT enough: a load/reload may have replaced
    // `messages` and detached that bubble (events then render into a dead object), and a queued
    // turn fired while the user was on another conversation never had a bubble at all. In both
    // cases the server's pending copy is the only way the card can reach the screen.
    const live = liveBubbles.get(sessionId)
    if (live && messages.value.includes(live)) {
      return
    }
    // An undecided card already on screen means we're synced — don't refetch on every live tick.
    const known = new Set<string>()
    let undecidedVisible = false
    for (const m of messages.value) {
      if (m.question?.requestId) {
        known.add(m.question.requestId)
        undecidedVisible ||= !m.question.answered
      }
      for (const p of m.permissions ?? []) {
        known.add(p.requestId)
        undecidedVisible ||= !p.decided
      }
    }
    if (undecidedVisible) {
      return
    }
    let asks
    try {
      asks = await api.pendingAsks(projectId, sessionId)
    } catch {
      return // engine without pending support / transient error
    }
    const fresh = asks.filter((a) => 'requestId' in a && a.requestId && !known.has(a.requestId))
    if (fresh.length === 0 || sessions.selectedId !== sessionId) {
      return
    }
    const bubble = makeMessage('assistant', '')
    for (const a of fresh) {
      if (a.type === 'permission') {
        bubble.permissions = bubble.permissions ?? []
        bubble.permissions.push({
          requestId: a.requestId,
          toolName: a.toolName,
          input: a.input,
          suggestions: a.suggestions,
          decided: false,
        })
      } else if (a.type === 'question') {
        bubble.question = { questions: a.questions, answered: false, requestId: a.requestId }
      }
    }
    messages.value.push(bubble)
  }

  function clear() {
    // Only resets the on-screen view; per-session turn state lives in `turns` and keeps running.
    messages.value = []
    search.value = ''
  }

  /**
   * The user's own prompts of the conversation on screen, oldest→newest — the composer's ↑-recall
   * history. Tool-result "user" lines are reclassified to role "tool" server-side, so user+hasText
   * filters to real prompts; adjacent duplicates collapse so cycling never repeats in a row.
   */
  function userPromptHistory(): string[] {
    const out: string[] = []
    for (const m of messages.value) {
      if (!m.user || !m.hasText) {
        continue
      }
      const t = m.text.trim()
      if (t && t !== out[out.length - 1]) {
        out.push(t)
      }
    }
    return out
  }

  /**
   * Send a message from the composer: it targets the conversation currently on screen. If that
   * conversation already has a turn in flight, the message is queued and sent as the next turn.
   */
  async function send(text: string, permissionMode?: string, model?: string,
                      attachments?: ChatAttachment[]) {
    const t = text.trim()
    if (!t && !(attachments && attachments.length > 0)) {
      return
    }
    // An armed edit&resend forks the conversation first; the message then goes to the fork (which
    // branchAt has selected). A failed fork aborts the send — falling through would append the
    // edited text to the ORIGINAL session instead.
    if (editPrevUuid.value) {
      const uuid = editPrevUuid.value
      cancelEditResend()
      if (!(await branchAt(uuid))) {
        return
      }
    }
    const sessions = useSessionsStore()
    const draft = sessions.draftMode
    const key = draft ? DRAFT_KEY : (sessions.selectedId ?? '')
    if (!draft && !key) {
      return
    }
    const st = turnFor(key)
    if (st.busy) {
      st.queue.push({ text: t, mode: permissionMode, model, attachments })
      return
    }
    const projects = useProjectsStore()
    const pid = projects.selectedId ?? sessions.projectId ?? ''
    if (!pid) {
      useUiStore().setError('Select a project first')
      return
    }
    await runTurn(key, pid, draft, t, permissionMode, model, attachments)
  }

  /**
   * Run one turn for a specific conversation `key`. Optimistic bubbles and post-turn reloads touch
   * the on-screen `messages` ONLY while that conversation is the one being viewed — so a turn left
   * running while the user switches away never clobbers the conversation now on screen.
   */
  async function runTurn(
    key: string,
    pid: string,
    draft: boolean,
    t: string,
    permissionMode?: string,
    model?: string,
    attachments?: ChatAttachment[],
  ) {
    const sessions = useSessionsStore()
    const st = turnFor(key)
    // Is this turn's conversation the one currently on screen?
    const viewed = () =>
      draft ? sessions.draftMode : !sessions.draftMode && sessions.selectedId === key

    // Optimistic bubbles — only into the visible conversation.
    let bubble: ChatMessage | null = null
    if (viewed()) {
      const userBubble = makeMessage('user', t)
      if (attachments && attachments.length > 0) {
        userBubble.attachments = attachments.map((a) => ({
          mediaType: a.mediaType,
          dataUrl: `data:${a.mediaType};base64,${a.data}`,
        }))
      }
      messages.value.push(userBubble)
      messages.value.push(makeMessage('assistant', ''))
      bubble = messages.value[messages.value.length - 1]
      liveBubbles.set(key, bubble)
    }

    st.busy = true
    st.pid = pid
    st.sid = draft ? null : key
    const controller = new AbortController()
    controllers.set(key, controller)
    let newSessionId: string | null = null
    let drainKey = key
    // CLI engine only: the server ends the turn at an interactive question (it can't be answered
    // mid-flight headlessly). Once seen, we drop any trailing output and keep the optimistic bubble
    // (skip the canonical reload), so the interactive card survives for the user to answer —
    // answerQuestion() resumes as the next turn. On the sdk engine the question carries a requestId,
    // the turn stays open, and the answer goes back INTO the turn — no special casing needed.
    let endedAtQuestion = false

    const onEvent = (ev: StreamEvent) => {
      switch (ev.type) {
        case 'session':
          newSessionId = ev.sessionId
          st.sid = ev.sessionId
          break
        case 'text-delta':
          if (bubble && !endedAtQuestion) {
            bubble.text += ev.text
            bubble.hasText = true
          }
          break
        case 'thinking-delta':
          if (bubble && !endedAtQuestion) {
            bubble.thinking = (bubble.thinking ?? '') + ev.text
            bubble.hasThinking = true
          }
          break
        case 'tool':
          if (bubble && !endedAtQuestion) {
            bubble.tools.push({ kind: 'use', title: ev.title, body: '', id: ev.toolUseId })
            bubble.hasTools = true
          }
          break
        case 'tool-result':
          if (bubble) {
            const block = bubble.tools.find((b) => b.id && b.id === ev.toolUseId)
            if (block && !block.body) {
              block.body = ev.body
            }
          }
          break
        case 'question':
          if (bubble) {
            bubble.question = { questions: ev.questions, answered: false, requestId: ev.requestId }
          }
          if (!ev.requestId) {
            endedAtQuestion = true // legacy cli engine — the turn is over at the question
          }
          maybeNotify('Claude has a question', ev.questions[0]?.question ?? 'Pick an answer')
          break
        case 'permission':
          if (bubble) {
            bubble.permissions = bubble.permissions ?? []
            bubble.permissions.push({
              requestId: ev.requestId,
              toolName: ev.toolName,
              input: ev.input,
              suggestions: ev.suggestions,
              decided: false,
            })
          }
          maybeNotify('Claude is waiting for permission', `Allow ${ev.toolName}?`)
          break
        case 'error':
          throw new Error(ev.message || 'chat failed')
        case 'done':
          break
      }
    }

    try {
      const body = { text: t, permissionMode, model, attachments }
      if (draft) {
        await api.startSession(pid, body, onEvent, controller.signal)
      } else {
        await api.sendMessage(pid, key, body, onEvent, controller.signal)
      }
      // Success.
      maybeNotify('Claude finished', bubble?.text || 'The turn completed')
      if (draft && newSessionId) {
        // The draft is now a real session: carry anything queued during the draft turn to it.
        const realSt = turnFor(newSessionId)
        realSt.queue.push(...st.queue)
        st.queue.length = 0
        if (viewed()) {
          if (endedAtQuestion) {
            // First turn ended on a question: adopt the real session id but keep the optimistic
            // bubble + interactive card on screen (don't clear/reload it away).
            await sessions.adoptCreated(pid, newSessionId)
          } else {
            // Foreground: promote the draft into the real session and show its canonical content.
            sessions.exitDraft()
            await sessions.loadFor(pid)
            await sessions.select(newSessionId)
          }
        }
        // else: still running but the user navigated away — let it finish; the new session shows up
        // in the list via the live refresh, and is loaded from disk when they open it.
        drainKey = newSessionId
      } else if (!draft && viewed() && !endedAtQuestion) {
        // Swap optimistic bubbles for canonical disk content (real tool blocks, server times).
        // Skipped for a question turn — reloading would replace the interactive card with the raw
        // AskUserQuestion tool block.
        await reload(pid, key)
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        if (bubble) {
          bubble.text = `${bubble.text}\n\n_⏹ stopped_`.trim()
          bubble.hasText = true
        }
      } else if (e instanceof ApiError && e.status === 409) {
        // A turn is still running for this session (started in another tab, or left running after a
        // page reload). Calm, actionable note instead of a scary error — the Stop button is shown
        // whenever the open session is live "working", so the user can cancel and resend.
        const note = 'This session still has a running turn — press Stop to cancel it, then resend.'
        if (bubble) {
          bubble.text = note
          bubble.hasText = true
        } else {
          useUiStore().setStatus(note)
        }
      } else if (bubble) {
        const msg = e instanceof Error ? e.message : String(e)
        bubble.text = `${bubble.text ? bubble.text + '\n\n' : ''}_⚠ ${msg}_`
        bubble.hasText = true
      } else {
        useUiStore().setError(e)
      }
    } finally {
      finishTurn(key)
    }

    // Turn done (completed, errored, or stopped): fire the next queued message for this conversation.
    dequeueNext(drainKey)
  }

  /** Mark a conversation's turn finished and drop its abort handle; prune the entry if fully idle. */
  function finishTurn(key: string) {
    controllers.delete(key)
    liveBubbles.delete(key)
    const st = turns.get(key)
    if (!st) {
      return
    }
    st.busy = false
    if (st.queue.length === 0) {
      turns.delete(key)
    }
  }

  /** Start the next queued message for `key` as a fresh turn, if that conversation is free. */
  function dequeueNext(key: string) {
    const st = turns.get(key)
    if (!st || st.busy) {
      return
    }
    const next = st.queue.shift()
    if (!next) {
      return
    }
    const pid =
      st.pid ?? useProjectsStore().selectedId ?? useSessionsStore().projectId ?? ''
    // A queued message is never a draft — a draft becomes a real id before its queue is drained.
    void runTurn(key, pid, false, next.text, next.mode, next.model, next.attachments)
  }

  /** Drop a single queued message from the on-screen conversation (the ✕ on a queued bubble). */
  function removeQueued(index: number) {
    turns.get(currentKey())?.queue.splice(index, 1)
  }

  /**
   * Answer an AskUserQuestion shown on `msg`. With a `requestId` (sdk engine) the answer is fed
   * back INTO the still-running turn, which then continues streaming on the open connection. Without
   * one (cli engine) the turn already ended at the question, so the picked text is sent as the next
   * turn (resume), reusing the session's current permission mode.
   */
  function answerQuestion(msg: ChatMessage, answerText: string, selections?: string[][]) {
    const requestId = msg.question?.requestId
    if (msg.question) {
      msg.question.answered = true
    }
    if (requestId && selections) {
      const st = turns.get(currentKey())
      const pid = st?.pid ?? useProjectsStore().selectedId ?? useSessionsStore().projectId
      const sid = st?.sid ?? (useSessionsStore().draftMode ? null : useSessionsStore().selectedId)
      if (pid && sid) {
        api.answerInTurn(pid, sid, requestId, selections).catch((e) => useUiStore().setError(e))
        return
      }
    }
    const sessions = useSessionsStore()
    const sid = sessions.draftMode ? null : sessions.selectedId
    void send(answerText, sessions.permissionModeFor(sid))
  }

  /**
   * Allow/deny a pending tool-permission card (sdk engine) — the paused turn then continues.
   * `always` (with allow) also persists the SDK's suggested rules so this tool stops asking.
   */
  function decidePermission(perm: PendingPermission, allow: boolean, opts?: { always?: boolean }) {
    if (perm.decided) {
      return
    }
    perm.decided = true
    perm.allowed = allow
    const st = turns.get(currentKey())
    const pid = st?.pid ?? useProjectsStore().selectedId ?? useSessionsStore().projectId
    const sid = st?.sid ?? (useSessionsStore().draftMode ? null : useSessionsStore().selectedId)
    if (pid && sid) {
      api
        .decidePermission(pid, sid, perm.requestId, allow ? 'allow' : 'deny', undefined, opts?.always ?? false)
        .catch((e) => useUiStore().setError(e))
    }
  }

  /**
   * Fork the open session at `msg`: the server copies the conversation up to that message into a
   * new session, which is then selected so the chat continues on the fork. No-op for messages
   * without a uuid (optimistic live bubbles — they're not on disk yet).
   */
  async function branchFrom(msg: ChatMessage) {
    if (msg.uuid) {
      await branchAt(msg.uuid)
    }
  }

  /** Fork the open session at the line `uuid` and select the fork. Returns false on failure. */
  async function branchAt(uuid: string): Promise<boolean> {
    const sessions = useSessionsStore()
    const pid = useProjectsStore().selectedId ?? sessions.projectId
    const sid = sessions.draftMode ? null : sessions.selectedId
    if (!pid || !sid) {
      return false
    }
    try {
      const created = await api.branch(pid, sid, uuid)
      await sessions.refreshList(pid)
      await sessions.select(created.sessionId)
      useUiStore().setStatus(`Forked into "${created.title}"`)
      return true
    } catch (e) {
      useUiStore().setError(e)
      return false
    }
  }

  // --- edit & resend: redo one of your prompts on a fork --------------------------------------
  // Armed by the ✎ button on a user message; the composer picks up the original text via
  // `editSeedText`. The next send FORKS the conversation at the message before the edited one and
  // sends the new text there — the original session stays intact. Disarmed on session switch.
  const editPrevUuid = ref<string | null>(null)
  const editSeedText = ref<string | null>(null)
  const editArmed = computed(() => editPrevUuid.value !== null)

  /** Arm edit&resend for `msg` (a user message on disk). False when there is no fork point. */
  function armEditResend(msg: ChatMessage): boolean {
    const i = messages.value.indexOf(msg)
    if (i < 0 || !msg.uuid) {
      return false
    }
    // The fork must end BEFORE the edited message — find the closest earlier on-disk line.
    for (let j = i - 1; j >= 0; j--) {
      const uuid = messages.value[j].uuid
      if (uuid) {
        editPrevUuid.value = uuid
        editSeedText.value = msg.text
        return true
      }
    }
    return false // the very first message — nothing before it to fork from
  }

  function cancelEditResend() {
    editPrevUuid.value = null
    editSeedText.value = null
  }

  /** The composer consumed the seeded text (it stays armed until sent or cancelled). */
  function clearEditSeed() {
    editSeedText.value = null
  }

  /** Whether THIS tab has a turn streaming for `key` (live.ts gates mid-turn disk reloads on it). */
  function isStreaming(key: string): boolean {
    return controllers.has(key)
  }

  /**
   * Stop the in-flight turn of a conversation (defaults to the one on screen): abort the stream and
   * ask the server to kill the CLI process. The queue is left intact, so the next message still goes.
   */
  function cancel(key: string = currentKey()) {
    controllers.get(key)?.abort()
    const st = turns.get(key)
    let pid = st?.pid ?? null
    let sid = st?.sid ?? null
    // No local turn state (page was reloaded, or the turn was started in another tab) but the open
    // session may still be running server-side — stop it by id. `key` is the session id for a
    // non-draft conversation; a draft has no server id until its `session` event, so only the abort
    // above applies (the server kills the process on the resulting disconnect).
    if (!sid && key !== DRAFT_KEY) {
      const sessions = useSessionsStore()
      sid = key || sessions.selectedId
      pid = pid ?? useProjectsStore().selectedId ?? sessions.projectId
    }
    if (pid && sid) {
      api.stopTurn(pid, sid).catch(() => {
        // best-effort; the aborted connection already triggers a server-side kill
      })
    }
  }

  return {
    messages,
    search,
    loading,
    busy,
    queue,
    filtered,
    load,
    reload,
    syncPendingAsks,
    isStreaming,
    clear,
    userPromptHistory,
    send,
    removeQueued,
    answerQuestion,
    decidePermission,
    branchFrom,
    editArmed,
    editSeedText,
    armEditResend,
    cancelEditResend,
    clearEditSeed,
    cancel,
  }
})
