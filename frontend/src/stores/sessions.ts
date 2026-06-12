import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { SessionInfo } from '@/types/models'
import { useConversationStore } from './conversation'
import { useLiveStore } from './live'
import { useUiStore } from './ui'

/** Per-session memory of the chat permission mode, with a global "last used" fallback. */
const PERM_KEY = 'claude.chat.permissionMode'
interface PermStore {
  last: string
  bySession: Record<string, string>
}
function loadPerms(): PermStore {
  try {
    const raw = localStorage.getItem(PERM_KEY)
    if (raw) {
      const p = JSON.parse(raw) as Partial<PermStore>
      return { last: p.last || 'plan', bySession: p.bySession || {} }
    }
  } catch {
    // fall through to default
  }
  return { last: 'plan', bySession: {} }
}

/** Per-session memory of the chat model alias ('' = default), mirroring the permission-mode store. */
const MODEL_KEY = 'claude.chat.model'
function loadModels(): PermStore {
  try {
    const raw = localStorage.getItem(MODEL_KEY)
    if (raw) {
      const p = JSON.parse(raw) as Partial<PermStore>
      return { last: p.last || '', bySession: p.bySession || {} }
    }
  } catch {
    // fall through to default
  }
  return { last: '', bySession: {} }
}

/** Per-session memory of unsent composer text, so switching sessions never loses a draft. */
const DRAFT_TEXT_KEY = 'claude.chat.draftText'
/** Map slot for the composer of a NEW chat (draft mode — no session id yet). */
const NEW_CHAT_SLOT = '__new__'
function loadDraftTexts(): Record<string, string> {
  try {
    const raw = localStorage.getItem(DRAFT_TEXT_KEY)
    if (raw) {
      return JSON.parse(raw) as Record<string, string>
    }
  } catch {
    // fall through to default
  }
  return {}
}

export const useSessionsStore = defineStore('sessions', () => {
  const items = ref<SessionInfo[]>([])
  const projectId = ref<string | null>(null)
  const selectedId = ref<string | null>(null)
  const loading = ref(false)
  /** "Draft" = an unsaved new chat: composer is shown with no selected session; first send creates it. */
  const draftMode = ref(false)

  const perms = loadPerms()
  const models = loadModels()
  const draftTexts = loadDraftTexts()

  const selected = computed(
    () => items.value.find((s) => s.sessionId === selectedId.value) ?? null,
  )

  async function loadFor(pid: string) {
    projectId.value = pid
    selectedId.value = null
    draftMode.value = false
    loading.value = true
    items.value = []
    useConversationStore().clear()
    try {
      const loaded = await api.sessions(pid)
      // Ignore a stale response if the user has since clicked another project.
      if (projectId.value !== pid) {
        return
      }
      items.value = loaded
      useLiveStore().reapply()
    } catch (e) {
      if (projectId.value === pid) {
        items.value = []
        useUiStore().setError(e)
      }
    } finally {
      if (projectId.value === pid) {
        loading.value = false
      }
    }
  }

  async function select(sessionId: string) {
    draftMode.value = false
    selectedId.value = sessionId
    if (projectId.value) {
      await useConversationStore().load(projectId.value, sessionId)
    }
  }

  /**
   * Refresh the session list in place (fetch + merge) WITHOUT clearing the open conversation or
   * resetting selection. Unlike {@link loadFor}, it never touches on-screen messages — used when a
   * turn must keep its optimistic bubble (e.g. an interactive question) while the list catches up.
   */
  async function refreshList(pid: string) {
    try {
      const loaded = await api.sessions(pid)
      if (projectId.value === pid) {
        mergeFrom(loaded)
      }
    } catch {
      // best-effort; the live refresh will reconcile the list shortly
    }
  }

  /**
   * Adopt a session the server just created for a draft, WITHOUT loading its canonical content — the
   * on-screen optimistic messages (e.g. an interactive question awaiting an answer) are preserved.
   * The new session is merged into the list so it appears and stays selected.
   */
  async function adoptCreated(pid: string, sessionId: string) {
    draftMode.value = false
    selectedId.value = sessionId
    projectId.value = pid
    await refreshList(pid)
  }

  /** Start a new in-browser chat in the current project (composer with no selected session). */
  function startDraft() {
    if (!projectId.value) {
      return
    }
    selectedId.value = null
    draftMode.value = true
    useConversationStore().clear()
  }

  function exitDraft() {
    draftMode.value = false
  }

  /** The chat permission mode to preselect: this session's, else the last used, else "plan". */
  function permissionModeFor(sessionId: string | null): string {
    if (sessionId && perms.bySession[sessionId]) {
      return perms.bySession[sessionId]
    }
    return perms.last || 'plan'
  }

  function setPermissionMode(sessionId: string | null, mode: string) {
    perms.last = mode
    if (sessionId) {
      perms.bySession[sessionId] = mode
    }
    try {
      localStorage.setItem(PERM_KEY, JSON.stringify(perms))
    } catch {
      // localStorage unavailable — keep the in-memory value only
    }
  }

  /** '' = the user's default model. */
  function modelFor(sessionId: string | null): string {
    if (sessionId && models.bySession[sessionId]) {
      return models.bySession[sessionId]
    }
    return models.last || ''
  }

  function setModel(sessionId: string | null, model: string) {
    models.last = model
    if (sessionId) {
      models.bySession[sessionId] = model
    }
    try {
      localStorage.setItem(MODEL_KEY, JSON.stringify(models))
    } catch {
      // localStorage unavailable — keep the in-memory value only
    }
  }

  /** The unsent composer text remembered for a session (null = the new-chat draft slot). */
  function draftTextFor(sessionId: string | null): string {
    return draftTexts[sessionId ?? NEW_CHAT_SLOT] ?? ''
  }

  /** Remember the unsent composer text of a session; blank text forgets the entry. */
  function setDraftText(sessionId: string | null, text: string) {
    const key = sessionId ?? NEW_CHAT_SLOT
    if (text) {
      draftTexts[key] = text
    } else if (key in draftTexts) {
      delete draftTexts[key]
    } else {
      return // nothing stored and nothing to store — skip the write
    }
    try {
      localStorage.setItem(DRAFT_TEXT_KEY, JSON.stringify(draftTexts))
    } catch {
      // localStorage unavailable — keep the in-memory value only
    }
  }

  /** Replace a single session in place (e.g. after a rename) without dropping selection. */
  function replace(updated: SessionInfo) {
    const i = items.value.findIndex((s) => s.sessionId === updated.sessionId)
    if (i >= 0) {
      items.value[i] = updated
    }
  }

  /**
   * Merge a freshly-scanned session list (from a live update) WITHOUT dropping selection or
   * live status. Carries each session's live status forward to avoid a flicker, and retains the
   * selected session even if it was deleted on disk. The list is keyed by sessionId in the
   * template, so replacing the array preserves scroll and the open conversation.
   */
  function mergeFrom(fresh: SessionInfo[]) {
    const prev = new Map(items.value.map((s) => [s.sessionId, s]))
    const result: SessionInfo[] = []
    for (const f of fresh) {
      const old = prev.get(f.sessionId)
      if (old) {
        f.liveStatus = old.liveStatus
      }
      result.push(f)
    }
    if (selectedId.value && !result.some((s) => s.sessionId === selectedId.value)) {
      const sel = prev.get(selectedId.value)
      if (sel) {
        result.push(sel)
      }
    }
    items.value = result
  }

  return {
    items,
    projectId,
    selectedId,
    loading,
    draftMode,
    selected,
    loadFor,
    refreshList,
    adoptCreated,
    select,
    startDraft,
    exitDraft,
    permissionModeFor,
    setPermissionMode,
    modelFor,
    setModel,
    draftTextFor,
    setDraftText,
    replace,
    mergeFrom,
  }
})
