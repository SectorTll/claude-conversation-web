import type { LiveSnapshot } from '@/types/models'

export interface ChangedSession {
  projectId: string
  sessionId: string
}

export interface SessionsChanged {
  changes: ChangedSession[]
}

export interface LiveHandlers {
  onLive: (snap: LiveSnapshot) => void
  onSessions: (payload: SessionsChanged) => void
  onOpen?: () => void
  onError?: () => void
}

/** Open the live SSE stream. The browser's EventSource auto-reconnects on drop. */
export function connectLive(h: LiveHandlers): EventSource {
  const es = new EventSource('/sse/live')
  es.addEventListener('live', (e) => h.onLive(JSON.parse((e as MessageEvent).data)))
  es.addEventListener('sessions', (e) => h.onSessions(JSON.parse((e as MessageEvent).data)))
  es.onopen = () => h.onOpen?.()
  es.onerror = () => h.onError?.()
  return es
}
