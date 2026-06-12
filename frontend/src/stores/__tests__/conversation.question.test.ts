import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { useConversationStore } from '../conversation'
import { useSessionsStore } from '../sessions'

// The store consumes the chat stream through `api`; mock it so we can hand-feed events and control
// when a turn ends.
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
    stopTurn: vi.fn(),
    messages: vi.fn(),
    sessions: vi.fn(),
  },
}))

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

const flush = () => new Promise((r) => setTimeout(r))

/** A single AskUserQuestion question with one option, the common case. */
function oneQuestion() {
  return [
    {
      question: 'Pick a framework',
      header: 'Framework',
      multiSelect: false,
      options: [{ label: 'Vue', description: 'the one in use' }],
    },
  ]
}

describe('conversation interactive questions (AskUserQuestion)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.messages).mockResolvedValue([])
    vi.mocked(api.sessions).mockResolvedValue([])
    vi.mocked(api.stopTurn).mockResolvedValue(undefined)
  })
  afterEach(() => vi.restoreAllMocks())

  function primeSession(sid = 's') {
    const conv = useConversationStore()
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = sid
    sessions.draftMode = false
    return { conv, sessions }
  }

  it('attaches a `question` event to the live assistant bubble', () => {
    const { conv } = primeSession()
    let emit!: (ev: unknown) => void
    const turn = deferred()
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      return turn.promise
    })

    conv.send('build me a component')
    emit({ type: 'question', questions: oneQuestion() })

    const bubble = conv.messages[conv.messages.length - 1]
    expect(bubble.question).toBeTruthy()
    expect(bubble.question!.answered).toBe(false)
    expect(bubble.question!.questions[0].options[0].label).toBe('Vue')
  })

  it('a question ends the turn without a canonical reload, and drops trailing output', async () => {
    const { conv } = primeSession()
    let emit!: (ev: unknown) => void
    const turn = deferred()
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      return turn.promise
    })

    conv.send('build me a component')
    emit({ type: 'question', questions: oneQuestion() })
    // The server ends the turn at the question; any model output after it is a fallback we must drop.
    emit({ type: 'text-delta', text: 'fallback that contradicts the question' })

    turn.resolve()
    await flush()

    // Reloading from disk would replace the interactive card with the raw AskUserQuestion tool block —
    // it must be skipped for a question turn.
    expect(api.messages).not.toHaveBeenCalled()
    const bubble = conv.messages[conv.messages.length - 1]
    expect(bubble.question).toBeTruthy()
    expect(bubble.question!.answered).toBe(false)
    expect(bubble.text).toBe('') // the trailing fallback delta was ignored
  })

  it('answerQuestion locks the choices and sends the pick as the next turn', async () => {
    const { conv } = primeSession()
    const turns: ReturnType<typeof deferred>[] = []
    let emit!: (ev: unknown) => void
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      const d = deferred()
      turns.push(d)
      return d.promise
    })

    conv.send('build me a component')
    emit({ type: 'question', questions: oneQuestion() })
    const bubble = conv.messages[conv.messages.length - 1]

    // Answering while the turn is still in flight queues the reply; it locks immediately.
    conv.answerQuestion(bubble, 'Vue')
    expect(bubble.question!.answered).toBe(true)

    turns[0].resolve() // first turn finishes → queued answer drains as the next turn
    await flush()

    expect(api.sendMessage).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.sendMessage).mock.calls[1][2]).toMatchObject({ text: 'Vue' })
  })
})
