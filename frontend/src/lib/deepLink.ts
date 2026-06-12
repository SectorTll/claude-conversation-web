// Deep links into a session: `#/p/<projectId>/s/<sessionId>`. Used by push-notification clicks
// (the service worker either postMessages an open tab or opens a new one with the hash).
import { useProjectsStore } from '@/stores/projects'
import { useSessionsStore } from '@/stores/sessions'

export function parseSessionPath(path: string): { projectId: string; sessionId: string } | null {
  const m = /^\/p\/([^/]+)\/s\/([^/]+)$/.exec(path)
  if (!m) {
    return null
  }
  try {
    return { projectId: decodeURIComponent(m[1]), sessionId: decodeURIComponent(m[2]) }
  } catch {
    return null
  }
}

/** Open the linked session (select project → load sessions → select). No-op on a bad path. */
export async function openSessionPath(path: string): Promise<void> {
  const target = parseSessionPath(path)
  if (!target) {
    return
  }
  const projects = useProjectsStore()
  const sessions = useSessionsStore()
  await projects.select(target.projectId) // sets selection + loads the session list
  await sessions.select(target.sessionId)
}

/**
 * Wire the two entry points once after login: a pending `#/p/…/s/…` hash (a push click opened a
 * fresh tab) and `open-session` messages from the service worker (a push click focused this tab).
 */
export function installDeepLinks(): void {
  const hash = window.location.hash
  if (hash.startsWith('#/p/')) {
    history.replaceState(null, '', window.location.pathname) // consume the hash
    void openSessionPath(hash.slice(1))
  }
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', (e: MessageEvent) => {
      const data = e.data as { type?: string; path?: string } | null
      if (data?.type === 'open-session' && typeof data.path === 'string') {
        void openSessionPath(data.path)
      }
    })
  }
}
