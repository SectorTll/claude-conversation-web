import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { SearchHit } from '@/types/models'
import { useConversationStore } from './conversation'
import { useProjectsStore } from './projects'
import { useSessionsStore } from './sessions'
import { useUiStore } from './ui'

export const useSearchStore = defineStore('search', () => {
  const active = ref(false)
  const loading = ref(false)
  const query = ref('')
  const results = ref<SearchHit[]>([])
  const selectedSessionId = ref<string | null>(null)
  let controller: AbortController | null = null

  async function run(q: string) {
    query.value = q
    if (q.trim().length < 2) {
      return
    }
    active.value = true
    loading.value = true
    controller?.abort()
    controller = new AbortController()
    const signal = controller.signal

    const ui = useUiStore()
    // Results render in the sessions column — a collapsed one would hide them.
    ui.expandPane('sessions')
    ui.busy = true
    ui.setStatus(`Searching “${q.trim()}”…`)
    try {
      results.value = await api.search(q.trim(), 400, signal)
      ui.setStatus(`${results.value.length} session(s) matched`)
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        ui.setError(e instanceof ApiError ? e : (e as Error))
      }
    } finally {
      // Only the latest request clears the busy/loading state; a superseded one already aborted.
      if (signal === controller?.signal) {
        loading.value = false
        ui.busy = false
      }
    }
  }

  function clear() {
    controller?.abort()
    active.value = false
    loading.value = false
    query.value = ''
    results.value = []
    selectedSessionId.value = null
    useUiStore().setStatus('Ready')
  }

  async function openHit(hit: SearchHit) {
    selectedSessionId.value = hit.session.sessionId
    const projects = useProjectsStore()
    const sessions = useSessionsStore()
    const conv = useConversationStore()
    // Load the hit's project sessions + conversation, but stay in search-results mode.
    projects.selectedId = hit.projectId
    await sessions.loadFor(hit.projectId)
    await sessions.select(hit.session.sessionId)
    conv.search = query.value.trim()
  }

  return { active, loading, query, results, selectedSessionId, run, clear, openHit }
})
