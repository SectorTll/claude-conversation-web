import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import { connectLive, type SessionsChanged } from '@/api/sse'
import type { LiveSnapshot, LiveStatus } from '@/types/models'
import { useConversationStore } from './conversation'
import { useProjectsStore } from './projects'
import { useSessionsStore } from './sessions'

function samePath(a: string | null, b: string | null): boolean {
  if (!a || !b) {
    return false
  }
  const norm = (s: string) => s.replace(/[\\/]+$/, '').toLowerCase()
  return norm(a) === norm(b)
}

/**
 * Live running state + on-disk change notifications over SSE. Everything is merged onto the loaded
 * projects/sessions IN PLACE so selection, scroll and the open conversation are never disturbed.
 */
export const useLiveStore = defineStore('live', () => {
  const waitingCount = ref(0)
  const bySession = ref<Record<string, LiveStatus>>({})
  const connected = ref(false)
  let source: EventSource | null = null
  let lastSnapshot: LiveSnapshot | null = null

  function applySnapshot(snap: LiveSnapshot) {
    lastSnapshot = snap
    waitingCount.value = snap.waitingCount

    const map: Record<string, LiveStatus> = {}
    for (const s of snap.sessions) {
      map[s.sessionId] = s.status
    }
    bySession.value = map

    // A WAITING chat turn means a permission/question card is parked server-side. If it's the OPEN
    // session and this tab isn't streaming the turn (page reloaded / second tab), pull the pending
    // cards in so they can be answered here. Idempotent + self-throttled inside the store.
    {
      const sessions = useSessionsStore()
      const sid = sessions.selectedId
      if (sid && !sessions.draftMode && map[sid] === 'WAITING' && sessions.projectId) {
        void useConversationStore().syncPendingAsks(sessions.projectId, sid)
      }
    }

    const sessions = useSessionsStore()
    for (const s of sessions.items) {
      const st = map[s.sessionId] ?? 'NONE'
      if (s.liveStatus !== st) {
        s.liveStatus = st
      }
    }

    const projects = useProjectsStore()
    // Project badge aggregates its sessions with WAITING > WORKING > IDLE: a parked card anywhere
    // in the project needs attention; otherwise busy beats an open-but-idle shell.
    const RANK: Record<LiveStatus, number> = { NONE: 0, IDLE: 1, WORKING: 2, WAITING: 3 }
    for (const p of projects.items) {
      let status: LiveStatus = 'NONE'
      for (const ls of snap.sessions) {
        if (samePath(ls.cwd, p.realPath) && RANK[ls.status] > RANK[status]) {
          status = ls.status
          if (status === 'WAITING') {
            break
          }
        }
      }
      if (p.liveStatus !== status) {
        p.liveStatus = status
      }
    }
  }

  async function onSessionsChanged(payload: SessionsChanged) {
    const sessions = useSessionsStore()
    const projects = useProjectsStore()
    const conv = useConversationStore()
    const changedProjects = new Set(payload.changes.map((c) => c.projectId))

    // Update project counts / times / order.
    try {
      projects.mergeFrom(await api.projects())
    } catch {
      // ignore
    }

    // Update the open project's session list in place.
    if (sessions.projectId && changedProjects.has(sessions.projectId)) {
      try {
        sessions.mergeFrom(await api.sessions(sessions.projectId))
      } catch {
        // ignore
      }
    }

    // Reload the open conversation if its file changed (e.g. the live session appended turns) —
    // but NOT while this tab is streaming a turn in it. The CLI appends to the .jsonl mid-turn, so
    // this fires while the optimistic bubble (with its interactive permission/question cards) is
    // the view; replacing it with disk content would detach the bubble, lose the cards, and show
    // AskUserQuestion as a raw tool block. The canonical reload happens at turn end in runTurn.
    const openSid = sessions.selectedId
    if (
      sessions.projectId &&
      openSid &&
      !conv.isStreaming(openSid) &&
      payload.changes.some((c) => c.projectId === sessions.projectId && c.sessionId === openSid)
    ) {
      await conv.reload(sessions.projectId, openSid)
    }
  }

  function connect() {
    disconnect()
    source = connectLive({
      onLive: applySnapshot,
      onSessions: onSessionsChanged,
      onOpen: () => {
        connected.value = true
      },
      onError: () => {
        connected.value = false
      },
    })
  }

  function disconnect() {
    source?.close()
    source = null
    connected.value = false
  }

  /**
   * Re-apply the last known live snapshot onto the currently-loaded projects/sessions. Needed
   * because the server only pushes a `live` event when status CHANGES, so freshly (re)loaded
   * lists would otherwise stay at NONE until the next change.
   */
  function reapply() {
    if (lastSnapshot) {
      applySnapshot(lastSnapshot)
    }
  }

  return { waitingCount, bySession, connected, applySnapshot, reapply, connect, disconnect }
})
