import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { ChatMessage } from '@/types/models'
import { useConversationStore } from '../conversation'
import { useSessionsStore } from '../sessions'

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {},
  api: {
    branch: vi.fn(),
    sendMessage: vi.fn(),
    startSession: vi.fn(),
    messages: vi.fn(),
    sessions: vi.fn(),
    pendingAsks: vi.fn(),
  },
}))

function msg(role: 'user' | 'assistant', text: string, uuid?: string): ChatMessage {
  return {
    role,
    uuid: uuid ?? null,
    timestamp: null,
    text,
    thinking: null,
    tools: [],
    user: role === 'user',
    assistant: role === 'assistant',
    tool: false,
    system: false,
    hasText: true,
    hasThinking: false,
    hasTools: false,
    roleHeader: role === 'user' ? 'You' : 'Claude',
    timeDisplay: '',
  }
}

describe('edit & resend (fork + redo a prompt)', () => {
  let conv: ReturnType<typeof useConversationStore>
  let sessions: ReturnType<typeof useSessionsStore>

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    conv = useConversationStore()
    sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = 's'
    sessions.draftMode = false
    vi.mocked(api.messages).mockResolvedValue([])
    vi.mocked(api.pendingAsks).mockResolvedValue([])
    vi.mocked(api.sendMessage).mockResolvedValue(undefined)
    conv.messages.push(
      msg('user', 'first', 'u1'),
      msg('assistant', 'reply', 'u2'),
      msg('user', 'redo me', 'u3'),
    )
  })

  it('arms with the original text and forks at the previous on-disk line on send', async () => {
    vi.mocked(api.branch).mockResolvedValue({ sessionId: 'fork', title: '⑂ t' } as never)
    vi.spyOn(sessions, 'refreshList').mockResolvedValue()
    vi.spyOn(sessions, 'select').mockImplementation(async (id) => {
      sessions.selectedId = id
    })

    expect(conv.armEditResend(conv.messages[2])).toBe(true)
    expect(conv.editArmed).toBe(true)
    expect(conv.editSeedText).toBe('redo me')

    await conv.send('redone better')

    // Forked at u2 (the message BEFORE the edited one), and the send went to the fork.
    expect(api.branch).toHaveBeenCalledWith('p', 's', 'u2')
    expect(vi.mocked(api.sendMessage).mock.calls[0][1]).toBe('fork')
    expect(conv.editArmed).toBe(false)
  })

  it('refuses to arm on the very first message (no fork point before it)', () => {
    expect(conv.armEditResend(conv.messages[0])).toBe(false)
    expect(conv.editArmed).toBe(false)
  })

  it('a failed fork aborts the send instead of hitting the original session', async () => {
    vi.mocked(api.branch).mockRejectedValue(new Error('disk full'))

    conv.armEditResend(conv.messages[2])
    await conv.send('redone')

    expect(api.sendMessage).not.toHaveBeenCalled()
    expect(conv.editArmed).toBe(false) // disarmed either way — no surprise fork on the NEXT send
  })

  it('cancelEditResend drops both the armed state and the seed', () => {
    conv.armEditResend(conv.messages[2])
    conv.cancelEditResend()
    expect(conv.editArmed).toBe(false)
    expect(conv.editSeedText).toBeNull()
  })
})
