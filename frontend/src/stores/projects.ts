import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { ProjectInfo } from '@/types/models'
import { useLiveStore } from './live'
import { useSessionsStore } from './sessions'
import { useUiStore } from './ui'

export const useProjectsStore = defineStore('projects', () => {
  const items = ref<ProjectInfo[]>([])
  const selectedId = ref<string | null>(null)
  const filter = ref('')

  const filtered = computed(() => {
    const q = filter.value.trim().toLowerCase()
    if (!q) {
      return items.value
    }
    return items.value.filter(
      (p) => p.realPath.toLowerCase().includes(q) || p.shortName.toLowerCase().includes(q),
    )
  })

  const selected = computed(
    () => items.value.find((p) => p.projectId === selectedId.value) ?? null,
  )

  async function load() {
    try {
      items.value = await api.projects()
      useLiveStore().reapply()
    } catch (e) {
      useUiStore().setError(e)
    }
  }

  async function select(projectId: string) {
    selectedId.value = projectId
    await useSessionsStore().loadFor(projectId)
  }

  /** Merge a fresh project list in place (counts/times/order) keeping selection + live status. */
  function mergeFrom(fresh: ProjectInfo[]) {
    const prev = new Map(items.value.map((p) => [p.projectId, p]))
    for (const f of fresh) {
      const old = prev.get(f.projectId)
      if (old) {
        f.liveStatus = old.liveStatus
      }
    }
    items.value = fresh
  }

  return { items, selectedId, filter, filtered, selected, load, select, mergeFrom }
})
