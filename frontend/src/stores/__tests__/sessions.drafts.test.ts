import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSessionsStore } from '../sessions'

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {},
  api: {},
}))

/** Tests run under node (no DOM) — give the store a real, inspectable localStorage. */
function stubLocalStorage() {
  const map = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
  })
  return map
}

describe('per-session composer drafts', () => {
  let stored: Map<string, string>
  beforeEach(() => {
    stored = stubLocalStorage()
    setActivePinia(createPinia())
  })
  afterEach(() => vi.unstubAllGlobals())

  it('remembers a draft per session and keeps sessions independent', () => {
    const sessions = useSessionsStore()
    sessions.setDraftText('a', 'unsent for A')
    sessions.setDraftText('b', 'unsent for B')
    expect(sessions.draftTextFor('a')).toBe('unsent for A')
    expect(sessions.draftTextFor('b')).toBe('unsent for B')
    expect(sessions.draftTextFor('c')).toBe('')
  })

  it('blank text forgets the stored draft (the post-send clear)', () => {
    const sessions = useSessionsStore()
    sessions.setDraftText('a', 'typed')
    sessions.setDraftText('a', '')
    expect(sessions.draftTextFor('a')).toBe('')
    expect(stored.get('claude.chat.draftText')).toBe('{}')
  })

  it('a null session id uses the new-chat slot, separate from real sessions', () => {
    const sessions = useSessionsStore()
    sessions.setDraftText(null, 'new-chat draft')
    sessions.setDraftText('a', 'session draft')
    expect(sessions.draftTextFor(null)).toBe('new-chat draft')
    expect(sessions.draftTextFor('a')).toBe('session draft')
  })

  it('drafts survive a page reload (restored from localStorage by a fresh store)', () => {
    useSessionsStore().setDraftText('a', 'survives F5')

    setActivePinia(createPinia()) // a "new tab": fresh pinia, same localStorage
    expect(useSessionsStore().draftTextFor('a')).toBe('survives F5')
  })

  it('stays silent when localStorage is unavailable (in-memory only)', () => {
    vi.stubGlobal('localStorage', undefined)
    setActivePinia(createPinia())
    const sessions = useSessionsStore()
    sessions.setDraftText('a', 'memory only')
    expect(sessions.draftTextFor('a')).toBe('memory only')
  })
})
