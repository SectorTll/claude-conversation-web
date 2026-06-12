import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { ScheduledTaskInfo, TaskSpec } from '@/types/models'
import { useUiStore } from './ui'

export const useScheduleStore = defineStore('schedule', () => {
  const tasks = ref<ScheduledTaskInfo[]>([])
  const supported = ref(true)
  const loading = ref(false)

  async function loadCapabilities() {
    try {
      supported.value = (await api.scheduleCapabilities()).scheduling
    } catch {
      supported.value = false
    }
  }

  async function load() {
    loading.value = true
    try {
      tasks.value = await api.scheduleTasks()
    } catch (e) {
      useUiStore().setError(e)
    } finally {
      loading.value = false
    }
  }

  async function create(spec: TaskSpec): Promise<boolean> {
    const ui = useUiStore()
    try {
      const t = await api.scheduleCreate(spec)
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
      await api.scheduleRun(name)
      ui.setStatus(`Started “${name}”`)
      await load()
    } catch (e) {
      ui.setError(e)
    }
  }

  async function open(name: string) {
    try {
      await api.scheduleOpen(name)
    } catch (e) {
      useUiStore().setError(e)
    }
  }

  async function remove(name: string) {
    const ui = useUiStore()
    try {
      await api.scheduleDelete(name, true)
      ui.setStatus(`Deleted “${name}”`)
      await load()
    } catch (e) {
      ui.setError(e)
    }
  }

  return { tasks, supported, loading, loadCapabilities, load, create, run, open, remove }
})
