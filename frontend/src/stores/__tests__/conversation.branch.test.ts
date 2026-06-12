import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { ChatMessage, SessionInfo } from '@/types/models'
import { useConversationStore } from '../conversation'
import { useSessionsStore } from '../sessions'

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
    branch: vi.fn(),
    messages: vi.fn(),
    sessions: vi.fn(),
    pendingAsks: vi.fn(),
  },
}))

function msg(uuid?: string): ChatMessage {
  return {
    role: 'assistant',
    uuid: uuid ?? null,
    timestamp: null,
    text: 'answer',
    thinking: null,
    tools: [],
    user: false,
    assistant: true,
    tool: false,
    system: false,
    hasText: true,
    hasThinking: false,
    hasTools: false,
    roleHeader: 'Claude',
    timeDisplay: '',
  }
}

describe('conversation.branchFrom (fork at a message)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.messages).mockResolvedValue([])
    vi.mocked(api.sessions).mockResolvedValue([])
    vi.mocked(api.pendingAsks).mockResolvedValue([])
  })
  afterEach(() => vi.restoreAllMocks())

  function prime() {
    const conv = useConversationStore()
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = 'orig'
    sessions.draftMode = false
    return { conv, sessions }
  }

  it('calls the branch endpoint and selects the created fork', async () => {
    const { conv, sessions } = prime()
    vi.mocked(api.branch).mockResolvedValue({
      sessionId: 'fork-1',
      title: '⑂ Original',
    } as SessionInfo)

    await conv.branchFrom(msg('a1'))

    expect(api.branch).toHaveBeenCalledWith('p', 'orig', 'a1')
    expect(api.sessions).toHaveBeenCalled() // refreshList
    expect(sessions.selectedId).toBe('fork-1')
  })

  it('is a no-op for messages without a uuid (optimistic bubbles)', async () => {
    const { conv } = prime()

    await conv.branchFrom(msg())

    expect(api.branch).not.toHaveBeenCalled()
  })

  it('is a no-op in draft mode (no session on disk yet)', async () => {
    const { conv, sessions } = prime()
    sessions.draftMode = true

    await conv.branchFrom(msg('a1'))

    expect(api.branch).not.toHaveBeenCalled()
  })
})
