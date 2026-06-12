import { defineStore } from 'pinia'
import { api } from '@/api/client'
import { useUiStore } from './ui'

export const useActionsStore = defineStore('actions', () => {
  async function run(label: string, fn: () => Promise<void>) {
    const ui = useUiStore()
    try {
      await fn()
      ui.setStatus(label)
    } catch (e) {
      ui.setError(e)
    }
  }

  function resume(projectId: string, sessionId: string, fork = false) {
    return run(fork ? 'Forking session in CLI…' : 'Resuming in CLI…', () =>
      api.resume(projectId, sessionId, fork),
    )
  }

  function newSession(projectId: string) {
    return run('Starting new session…', () => api.newSession(projectId))
  }

  function openFolder(projectId: string, sessionId?: string) {
    return run('Opening folder…', () => api.openFolder(projectId, sessionId))
  }

  function revealFile(projectId: string, sessionId: string) {
    return run('Revealing file…', () => api.revealFile(projectId, sessionId))
  }

  async function copyResumeCommand(sessionId: string, fork = false) {
    const ui = useUiStore()
    const cmd = `claude --resume ${sessionId}${fork ? ' --fork-session' : ''}`
    try {
      await navigator.clipboard.writeText(cmd)
      ui.setStatus('Resume command copied to clipboard')
    } catch {
      ui.setStatus(cmd)
    }
  }

  return { resume, newSession, openFolder, revealFile, copyResumeCommand }
})
