import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { useConversationStore } from '../conversation'
import { useSessionsStore } from '../sessions'

// The sdk engine adds two interactive flows on a LIVE turn: tool-permission cards (allow/deny via
// REST while the stream stays open) and in-turn questions (a `question` event WITH a requestId —
// answered into the same turn instead of ending it). These tests pin both against the store.
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
    decidePermission: vi.fn(),
    answerInTurn: vi.fn(),
    pendingAsks: vi.fn(),
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

describe('conversation interactive permissions + in-turn questions (sdk engine)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.messages).mockResolvedValue([])
    vi.mocked(api.sessions).mockResolvedValue([])
    vi.mocked(api.stopTurn).mockResolvedValue(undefined)
    vi.mocked(api.decidePermission).mockResolvedValue(undefined)
    vi.mocked(api.answerInTurn).mockResolvedValue(undefined)
    vi.mocked(api.pendingAsks).mockResolvedValue([])
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

  it('attaches a `permission` event as an undecided card on the live bubble', () => {
    const { conv } = primeSession()
    let emit!: (ev: unknown) => void
    const turn = deferred()
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      return turn.promise
    })

    conv.send('run the tests')
    emit({ type: 'permission', requestId: 'r1', toolName: 'Bash', input: { command: 'npm test' } })

    const bubble = conv.messages[conv.messages.length - 1]
    expect(bubble.permissions).toHaveLength(1)
    expect(bubble.permissions![0]).toMatchObject({
      requestId: 'r1',
      toolName: 'Bash',
      decided: false,
    })
  })

  it('decidePermission locks the card, posts the decision, and the SAME turn keeps streaming', async () => {
    const { conv } = primeSession()
    let emit!: (ev: unknown) => void
    const turn = deferred()
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      return turn.promise
    })

    conv.send('run the tests')
    emit({ type: 'permission', requestId: 'r1', toolName: 'Bash', input: {} })
    const bubble = conv.messages[conv.messages.length - 1]
    const perm = bubble.permissions![0]

    conv.decidePermission(perm, true)
    expect(perm.decided).toBe(true)
    expect(perm.allowed).toBe(true)
    expect(api.decidePermission).toHaveBeenCalledWith('p', 's', 'r1', 'allow', undefined, false)

    // The turn was paused, not ended — output after the decision still lands in the same bubble.
    emit({ type: 'text-delta', text: 'tests passed' })
    expect(bubble.text).toBe('tests passed')
    expect(conv.busy).toBe(true)

    // No second turn was started by the decision.
    expect(api.sendMessage).toHaveBeenCalledTimes(1)
    turn.resolve()
    await flush()
  })

  it('always-allow posts the always flag', () => {
    const { conv } = primeSession()
    let emit!: (ev: unknown) => void
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      return deferred().promise
    })

    conv.send('run the tests')
    emit({ type: 'permission', requestId: 'r1', toolName: 'Bash', input: {}, suggestions: [{ rule: 'x' }] })
    const perm = conv.messages[conv.messages.length - 1].permissions![0]

    conv.decidePermission(perm, true, { always: true })
    expect(api.decidePermission).toHaveBeenCalledWith('p', 's', 'r1', 'allow', undefined, true)
  })

  it('a decided card cannot be re-decided', () => {
    const { conv } = primeSession()
    let emit!: (ev: unknown) => void
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      return deferred().promise
    })

    conv.send('run the tests')
    emit({ type: 'permission', requestId: 'r1', toolName: 'Bash', input: {} })
    const perm = conv.messages[conv.messages.length - 1].permissions![0]

    conv.decidePermission(perm, false)
    conv.decidePermission(perm, true) // second click must be a no-op
    expect(perm.allowed).toBe(false)
    expect(api.decidePermission).toHaveBeenCalledTimes(1)
  })

  it('a question WITH requestId stays in-turn: answered via REST, no next turn, reload allowed', async () => {
    const { conv } = primeSession()
    let emit!: (ev: unknown) => void
    const turn = deferred()
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      return turn.promise
    })

    conv.send('build me a component')
    emit({ type: 'question', requestId: 'q1', questions: oneQuestion() })
    const bubble = conv.messages[conv.messages.length - 1]
    expect(bubble.question!.requestId).toBe('q1')

    conv.answerQuestion(bubble, 'Vue', [['Vue']])
    expect(bubble.question!.answered).toBe(true)
    expect(api.answerInTurn).toHaveBeenCalledWith('p', 's', 'q1', [['Vue']])
    // The answer goes INTO the running turn — it must not queue/send a second turn.
    expect(api.sendMessage).toHaveBeenCalledTimes(1)

    // The model continues the same turn with the answer.
    emit({ type: 'text-delta', text: 'using Vue then' })
    expect(bubble.text).toBe('using Vue then')

    turn.resolve()
    await flush()
    // Unlike the cli engine, the canonical reload IS correct here (the .jsonl has the real blocks).
    expect(api.messages).toHaveBeenCalled()
  })

  it('syncPendingAsks re-attaches cards a reloaded tab never saw, without duplicating', async () => {
    const { conv } = primeSession()
    // No local stream for 's' (page was reloaded mid-turn) — the server still holds the cards.
    vi.mocked(api.pendingAsks).mockResolvedValue([
      { type: 'permission', requestId: 'r1', toolName: 'Bash', input: { command: 'ls' }, suggestions: [] },
      { type: 'question', requestId: 'q1', questions: oneQuestion() },
    ])

    await conv.syncPendingAsks('p', 's')
    const bubble = conv.messages[conv.messages.length - 1]
    expect(bubble.permissions).toHaveLength(1)
    expect(bubble.permissions![0].requestId).toBe('r1')
    expect(bubble.question!.requestId).toBe('q1')

    // While an undecided card is visible, repeat syncs (live ticks every 2s) must not refetch.
    await conv.syncPendingAsks('p', 's')
    expect(api.pendingAsks).toHaveBeenCalledTimes(1)
    expect(conv.messages.filter((m) => m.permissions?.length).length).toBe(1)
  })

  it('reload carries undecided cards over the canonical replace and drops settled ones', async () => {
    const { conv } = primeSession()
    // No local stream (page was reloaded mid-turn) — the cards arrived via syncPendingAsks.
    vi.mocked(api.pendingAsks).mockResolvedValue([
      { type: 'permission', requestId: 'r1', toolName: 'Bash', input: {}, suggestions: [] },
      { type: 'question', requestId: 'q1', questions: oneQuestion() },
    ])
    await conv.syncPendingAsks('p', 's')
    const bubble = conv.messages[conv.messages.length - 1]
    conv.decidePermission(bubble.permissions![0], true) // settled — must NOT survive the reload

    // The watcher fires on the .jsonl change: canonical disk content never carries cards.
    vi.mocked(api.messages).mockResolvedValue([])
    await conv.reload('p', 's')

    expect(conv.messages).toHaveLength(1)
    const carried = conv.messages[0]
    expect(carried.question!.requestId).toBe('q1')
    expect(carried.question!.answered).toBe(false)
    expect(carried.permissions ?? []).toHaveLength(0)
  })

  it('a card streamed in this tab is re-fetched after a load() detached the live bubble', async () => {
    const { conv } = primeSession()
    let emit!: (ev: unknown) => void
    vi.mocked(api.sendMessage).mockImplementation((_p, _s, _b, onEvent) => {
      emit = onEvent as typeof emit
      return deferred().promise
    })

    conv.send('build me a component')
    emit({ type: 'question', requestId: 'q1', questions: oneQuestion() })
    // While the streamed bubble (with its card) is on screen, sync must not refetch.
    await conv.syncPendingAsks('p', 's')
    expect(api.pendingAsks).not.toHaveBeenCalled()

    // A wholesale replace (switching away and back) detaches the streamed bubble — the card on it
    // is gone, and later stream events render into a dead object. The server copy is the recovery.
    vi.mocked(api.messages).mockResolvedValue([])
    vi.mocked(api.pendingAsks).mockResolvedValue([
      { type: 'question', requestId: 'q1', questions: oneQuestion() },
    ])
    await conv.load('p', 's')
    await flush()

    const last = conv.messages[conv.messages.length - 1]
    expect(last.question!.requestId).toBe('q1')
    expect(last.question!.answered).toBe(false)
  })

  it('a question WITHOUT requestId keeps the legacy cli behavior (skip reload, answer = next turn)', async () => {
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
    emit({ type: 'text-delta', text: 'fallback to drop' })
    const bubble = conv.messages[conv.messages.length - 1]

    conv.answerQuestion(bubble, 'Vue', [['Vue']])
    expect(api.answerInTurn).not.toHaveBeenCalled()

    turns[0].resolve()
    await flush()

    expect(api.messages).not.toHaveBeenCalled() // reload skipped — card must survive
    expect(bubble.text).toBe('') // trailing fallback dropped
    expect(api.sendMessage).toHaveBeenCalledTimes(2) // the answer went out as the next turn
    expect(vi.mocked(api.sendMessage).mock.calls[1][2]).toMatchObject({ text: 'Vue' })
  })
})
