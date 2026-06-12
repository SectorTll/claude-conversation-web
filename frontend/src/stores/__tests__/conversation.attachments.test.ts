import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
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
    sendMessage: vi.fn(),
    startSession: vi.fn(),
    messages: vi.fn(),
    sessions: vi.fn(),
    pendingAsks: vi.fn(),
  },
}))

const PNG = { mediaType: 'image/png', data: 'aWJt' }

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

const flush = () => new Promise((r) => setTimeout(r))

describe('conversation attachments (pasted images)', () => {
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
    sessions.selectedId = 's'
    sessions.draftMode = false
    return { conv, sessions }
  }

  it('sends attachments in the request body and renders chips on the optimistic user bubble', async () => {
    const { conv } = prime()
    const turn = deferred()
    vi.mocked(api.sendMessage).mockImplementation(() => turn.promise)

    conv.send('look at this', 'plan', '', [PNG])

    expect(api.sendMessage).toHaveBeenCalledWith(
      'p',
      's',
      expect.objectContaining({ text: 'look at this', attachments: [PNG] }),
      expect.any(Function),
      expect.anything(),
    )
    const userBubble = conv.messages[conv.messages.length - 2]
    expect(userBubble.attachments).toHaveLength(1)
    expect(userBubble.attachments![0].dataUrl).toBe('data:image/png;base64,aWJt')
    turn.resolve()
    await flush()
  })

  it('an image-only message (no text) still sends', async () => {
    const { conv } = prime()
    const turn = deferred()
    vi.mocked(api.sendMessage).mockImplementation(() => turn.promise)

    conv.send('', 'plan', '', [PNG])

    expect(api.sendMessage).toHaveBeenCalledTimes(1)
    turn.resolve()
    await flush()
  })

  it('a message queued while busy keeps its attachments for the next turn', async () => {
    const { conv } = prime()
    const turns: ReturnType<typeof deferred>[] = []
    vi.mocked(api.sendMessage).mockImplementation(() => {
      const d = deferred()
      turns.push(d)
      return d.promise
    })

    conv.send('first', 'plan', '')
    await conv.send('second with image', 'plan', '', [PNG])
    expect(conv.queue[0].attachments).toEqual([PNG])

    turns[0].resolve()
    await flush()
    expect(api.sendMessage).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.sendMessage).mock.calls[1][2]).toMatchObject({ attachments: [PNG] })
  })
})
