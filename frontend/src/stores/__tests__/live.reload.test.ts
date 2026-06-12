import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { connectLive, type LiveHandlers } from '@/api/sse'
import { useConversationStore } from '../conversation'
import { useLiveStore } from '../live'
import { useSessionsStore } from '../sessions'

// The watcher-triggered reload of the OPEN conversation must yield to a turn this tab is
// streaming: the CLI appends to the .jsonl mid-turn, and replacing the streamed view with disk
// content would destroy the optimistic bubble — including any interactive permission/question
// card parked on it (the canonical .jsonl renders AskUserQuestion as a raw tool block).
vi.mock('@/api/sse', () => ({ connectLive: vi.fn(() => ({ close: vi.fn() })) }))
vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    constructor(
      public status: number,
      message: string,
    ) {
      super(message)
    }
  },
  api: {
    projects: vi.fn(),
    sessions: vi.fn(),
    messages: vi.fn(),
    sendMessage: vi.fn(),
    pendingAsks: vi.fn(),
  },
}))

describe('live onSessionsChanged vs a streaming turn', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.projects).mockResolvedValue([])
    vi.mocked(api.sessions).mockResolvedValue([])
    vi.mocked(api.messages).mockResolvedValue([])
    vi.mocked(api.pendingAsks).mockResolvedValue([])
  })

  function wire(): LiveHandlers {
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = 's'
    sessions.draftMode = false
    useLiveStore().connect()
    return vi.mocked(connectLive).mock.calls[0][0]
  }

  it('reloads the open conversation on its file change when no local turn streams it', async () => {
    const handlers = wire()
    await handlers.onSessions({ changes: [{ projectId: 'p', sessionId: 's' }] })
    expect(api.messages).toHaveBeenCalledWith('p', 's')
  })

  it('skips the reload while THIS tab streams a turn for the open session', async () => {
    const handlers = wire()
    const conv = useConversationStore()
    vi.mocked(api.sendMessage).mockReturnValue(new Promise(() => {})) // turn never ends
    void conv.send('go') // session 's' now streams in this tab

    await handlers.onSessions({ changes: [{ projectId: 'p', sessionId: 's' }] })
    expect(api.messages).not.toHaveBeenCalled()
  })
})
