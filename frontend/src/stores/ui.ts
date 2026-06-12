import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { SessionInfo } from '@/types/models'

export interface RenameTarget {
  projectId: string
  session: SessionInfo
}

export type CollapsiblePane = 'projects' | 'sessions'

/** Collapsed state of the projects/sessions columns, persisted across reloads. */
const COLLAPSED_KEY = 'claude.ui.collapsedPanes'
function loadCollapsed(): Record<CollapsiblePane, boolean> {
  try {
    const raw = localStorage.getItem(COLLAPSED_KEY)
    if (raw) {
      const p = JSON.parse(raw) as Partial<Record<CollapsiblePane, boolean>>
      return { projects: !!p.projects, sessions: !!p.sessions }
    }
  } catch {
    // fall through to default
  }
  return { projects: false, sessions: false }
}

export const useUiStore = defineStore('ui', () => {
  const status = ref('Ready')
  const busy = ref(false)
  const scheduleOpen = ref(false)
  const serverScheduleOpen = ref(false)
  // "Trust this server's certificate" helper — opened when web-push finds an untrusted origin.
  const certHelpOpen = ref(false)
  // Telegram notification settings dialog (✈ button in the header).
  const telegramOpen = ref(false)
  const renameTarget = ref<RenameTarget | null>(null)
  const collapsed = loadCollapsed()
  const projectsCollapsed = ref(collapsed.projects)
  const sessionsCollapsed = ref(collapsed.sessions)

  function setStatus(s: string) {
    status.value = s
  }
  function setError(e: unknown) {
    status.value = e instanceof Error ? e.message : String(e)
  }
  function openRename(target: RenameTarget) {
    renameTarget.value = target
  }
  function closeRename() {
    renameTarget.value = null
  }
  function persistCollapsed() {
    try {
      localStorage.setItem(
        COLLAPSED_KEY,
        JSON.stringify({ projects: projectsCollapsed.value, sessions: sessionsCollapsed.value }),
      )
    } catch {
      // localStorage unavailable — keep the in-memory value only
    }
  }
  function togglePane(pane: CollapsiblePane) {
    const target = pane === 'projects' ? projectsCollapsed : sessionsCollapsed
    target.value = !target.value
    persistCollapsed()
  }
  function expandPane(pane: CollapsiblePane) {
    const target = pane === 'projects' ? projectsCollapsed : sessionsCollapsed
    if (target.value) {
      target.value = false
      persistCollapsed()
    }
  }

  return {
    status,
    busy,
    scheduleOpen,
    serverScheduleOpen,
    certHelpOpen,
    telegramOpen,
    renameTarget,
    projectsCollapsed,
    sessionsCollapsed,
    setStatus,
    setError,
    openRename,
    closeRename,
    togglePane,
    expandPane,
  }
})
