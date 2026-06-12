import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { ServerScheduledTask, ServerTaskSpec } from '@/types/models'
import { useUiStore } from './ui'

/** State for the second, in-process scheduler (mirrors `schedule.ts` plus enable/report extras). */
export const useServerScheduleStore = defineStore('serverSchedule', () => {
  const tasks = ref<ServerScheduledTask[]>([])
  const supported = ref(true)
  const loading = ref(false)
  const report = ref<{ name: string; content: string } | null>(null)

  async function loadCapabilities() {
    try {
      supported.value = (await api.serverScheduleCapabilities()).scheduling
    } catch {
      supported.value = false
    }
  }

  async function load() {
    loading.value = true
    try {
      tasks.value = await api.serverScheduleTasks()
    } catch (e) {
      useUiStore().setError(e)
    } finally {
      loading.value = false
    }
  }

  async function create(spec: ServerTaskSpec): Promise<boolean> {
    const ui = useUiStore()
    try {
      const t = await api.serverScheduleCreate(spec)
      ui.setStatus(`Scheduled “${t.name}”`)
      await load()
      return true
    } catch (e) {
      ui.setError(e)
      return false
    }
  }

  async function run(name: string) {
    const ui = useUiStore()
    try {
      await api.serverScheduleRun(name)
      ui.setStatus(`Started “${name}”`)
      await load()
    } catch (e) {
      ui.setError(e)
    }
  }

  async function setEnabled(name: string, value: boolean) {
    try {
      await api.serverScheduleSetEnabled(name, value)
      await load()
    } catch (e) {
      useUiStore().setError(e)
    }
  }

  async function open(name: string) {
    try {
      await api.serverScheduleOpen(name)
    } catch (e) {
      useUiStore().setError(e)
    }
  }

  async function loadReport(name: string) {
    try {
      report.value = await api.serverScheduleReport(name)
    } catch (e) {
      useUiStore().setError(e)
    }
  }

  function clearReport() {
    report.value = null
  }

  async function remove(name: string) {
    const ui = useUiStore()
    try {
      await api.serverScheduleDelete(name)
      ui.setStatus(`Deleted “${name}”`)
      await load()
    } catch (e) {
      ui.setError(e)
    }
  }

  return {
    tasks,
    supported,
    loading,
    report,
    loadCapabilities,
    load,
    create,
    run,
    setEnabled,
    open,
    loadReport,
    clearReport,
    remove,
  }
})
