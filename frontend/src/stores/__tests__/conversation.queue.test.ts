import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { useConversationStore } from '../conversation'
import { useSessionsStore } from '../sessions'

// The conversation store drives chat turns through `api`; mock it so we control when a turn ends.
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

/** A promise whose resolution we trigger by hand — lets a turn stay "in flight" mid-test. */
function deferred() {
  let resolve!: () => void
  let reject!: (e: unknown) => void
  const promise = new Promise<void>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** Let queued microtasks settle (e.g. the not-awaited send() kicked off by dequeueNext). */
const flush = () => new Promise((r) => setTimeout(r))

describe('conversation queue (type-while-busy)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.messages).mockResolvedValue([])
    vi.mocked(api.sessions).mockResolvedValue([])
    vi.mocked(api.stopTurn).mockResolvedValue(undefined)
  })
  afterEach(() => vi.restoreAllMocks())

  /** Prime a selected (non-draft) session and make each sendMessage stay pending until resolved. */
  function setupSession(sid = 's') {
    const conv = useConversationStore()
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = sid
    sessions.draftMode = false
    const turns: ReturnType<typeof deferred>[] = []
    vi.mocked(api.sendMessage).mockImplementation(() => {
      const d = deferred()
      turns.push(d)
      return d.promise
    })
    return { conv, sessions, turns }
  }

  it('queues a message typed while a turn is in flight instead of sending it', async () => {
    const { conv, turns } = setupSession()

    conv.send('first') // starts a turn — stays pending (not awaited)
    expect(conv.busy).toBe(true)

    await conv.send('second') // busy → queued, returns immediately
    expect(conv.queue.map((q) => q.text)).toEqual(['second'])
    expect(api.sendMessage).toHaveBeenCalledTimes(1)

    turns[0].resolve() // first turn finishes
    await flush()

    // The queued message is auto-sent as the next turn and leaves the queue.
    expect(conv.queue).toHaveLength(0)
    expect(api.sendMessage).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.sendMessage).mock.calls[1][2]).toMatchObject({ text: 'second' })
  })

  it('drains multiple queued messages in FIFO order', async () => {
    const { conv, turns } = setupSession()

    conv.send('first')
    await conv.send('second')
    await conv.send('third')
    expect(conv.queue.map((q) => q.text)).toEqual(['second', 'third'])

    turns[0].resolve()
    await flush()
    expect(vi.mocked(api.sendMessage).mock.calls[1][2]).toMatchObject({ text: 'second' })
    expect(conv.queue.map((q) => q.text)).toEqual(['third'])

    turns[1].resolve()
    await flush()
    expect(vi.mocked(api.sendMessage).mock.calls[2][2]).toMatchObject({ text: 'third' })
    expect(conv.queue).toHaveLength(0)
  })

  it('carries the per-message permission mode into the queued turn', async () => {
    const { conv, turns } = setupSession()

    conv.send('first', 'plan')
    await conv.send('second', 'acceptEdits')
    expect(conv.queue[0].mode).toBe('acceptEdits')

    turns[0].resolve()
    await flush()
    expect(vi.mocked(api.sendMessage).mock.calls[1][2]).toMatchObject({
      text: 'second',
      permissionMode: 'acceptEdits',
    })
  })

  it('removeQueued drops only the targeted message', async () => {
    const { conv } = setupSession()
    conv.send('first')
    await conv.send('a')
    await conv.send('b')
    await conv.send('c')

    conv.removeQueued(1) // remove 'b'
    expect(conv.queue.map((q) => q.text)).toEqual(['a', 'c'])
  })

  it('Stop (cancel) does NOT clear the queue — the next message still auto-sends', async () => {
    const { conv, turns } = setupSession()
    conv.send('first')
    await conv.send('second')

    conv.cancel() // abort current turn; queue must survive
    expect(conv.queue.map((q) => q.text)).toEqual(['second'])
    expect(api.stopTurn).toHaveBeenCalledWith('p', 's')

    turns[0].resolve() // aborted/finished turn settles
    await flush()
    expect(conv.queue).toHaveLength(0)
    expect(api.sendMessage).toHaveBeenCalledTimes(2)
  })

  it('clear() only resets the view; a session keeps its queue (it is per-session)', async () => {
    const { conv } = setupSession()
    conv.send('first')
    await conv.send('second')
    expect(conv.queue).toHaveLength(1)

    conv.clear() // view-only reset (project/session switch) — must not drop the running queue
    expect(conv.queue).toHaveLength(1)
  })

  it('preserves the queue across the draft→real session transition', async () => {
    const conv = useConversationStore()
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.draftMode = true
    sessions.selectedId = null

    // The draft turn emits a `session` event (assigning the new id) then stays pending.
    let draftEvent!: (ev: { type: 'session'; sessionId: string }) => void
    const draftTurn = deferred()
    vi.mocked(api.startSession).mockImplementation((_p, _b, onEvent) => {
      draftEvent = onEvent as typeof draftEvent
      return draftTurn.promise
    })
    // loadFor/select switch sessions and reset the view; the queued message must survive and end up
    // sent to the new real session id.
    vi.spyOn(sessions, 'loadFor').mockImplementation(async () => conv.clear())
    vi.spyOn(sessions, 'select').mockImplementation(async () => {
      sessions.selectedId = 'new'
      sessions.draftMode = false
      conv.clear()
    })
    vi.mocked(api.sendMessage).mockImplementation(() => deferred().promise)

    conv.send('hello') // draft turn
    draftEvent({ type: 'session', sessionId: 'new' })
    await conv.send('queued-during-draft') // queued while the draft turn runs
    expect(conv.queue).toHaveLength(1)

    draftTurn.resolve() // draft finishes → transitions to the real session
    await flush()

    // The queued message survived the transition and was sent to the now-real session.
    expect(conv.queue).toHaveLength(0)
    expect(api.sendMessage).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.sendMessage).mock.calls[0][0]).toBe('p')
    expect(vi.mocked(api.sendMessage).mock.calls[0][1]).toBe('new')
    expect(vi.mocked(api.sendMessage).mock.calls[0][2]).toMatchObject({ text: 'queued-during-draft' })
  })
})

describe('conversation per-session isolation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.messages).mockResolvedValue([])
  })
  afterEach(() => vi.restoreAllMocks())

  /** Each sendMessage stays pending until its deferred is resolved. */
  function pendingSends() {
    const turns: ReturnType<typeof deferred>[] = []
    vi.mocked(api.sendMessage).mockImplementation(() => {
      const d = deferred()
      turns.push(d)
      return d.promise
    })
    return turns
  }

  it('a turn in one conversation leaves a neighbour idle and usable', () => {
    pendingSends()
    const conv = useConversationStore()
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = 'a'
    sessions.draftMode = false

    conv.send('hi A') // turn running in A (not awaited)
    expect(conv.busy).toBe(true)

    sessions.selectedId = 'b' // switch to the neighbour, where nothing is running
    expect(conv.busy).toBe(false) // B is NOT blocked by A's turn
    expect(conv.queue).toHaveLength(0)

    conv.send('hi B') // B sends its own turn straight away
    expect(conv.busy).toBe(true)
    expect(api.sendMessage).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.sendMessage).mock.calls[0][1]).toBe('a')
    expect(vi.mocked(api.sendMessage).mock.calls[1][1]).toBe('b')
  })

  it('a background turn drains its own queue to its own session, without touching the viewed one', async () => {
    const turns = pendingSends()
    const conv = useConversationStore()
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = 'a'
    sessions.draftMode = false

    conv.send('a1') // turn running in A
    await conv.send('a2') // queued for A

    sessions.selectedId = 'b' // navigate away to B before A finishes
    expect(conv.busy).toBe(false) // viewing the idle neighbour

    turns[0].resolve() // A's turn completes in the background
    await flush()

    // The queued 'a2' was sent to session A (not B), and the viewed conversation B was never reloaded.
    const drained = vi.mocked(api.sendMessage).mock.calls[1]
    expect(drained[1]).toBe('a')
    expect(drained[2]).toMatchObject({ text: 'a2' })
    expect(api.messages).not.toHaveBeenCalled() // completing A did not clobber B's view
  })
})

describe('conversation stop / conflict recovery', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.messages).mockResolvedValue([])
    vi.mocked(api.stopTurn).mockResolvedValue(undefined)
  })
  afterEach(() => vi.restoreAllMocks())

  it('shows a calm note (not a scary error) when the server reports a turn already running (409)', async () => {
    const conv = useConversationStore()
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = 's'
    sessions.draftMode = false
    vi.mocked(api.sendMessage).mockRejectedValue(new ApiError(409, 'a chat turn is already running'))

    await conv.send('hello')

    const last = conv.messages[conv.messages.length - 1]
    expect(last.text).toContain('running turn') // calm, actionable wording
    expect(last.text).not.toContain('⚠')
    expect(conv.busy).toBe(false) // not stuck busy
  })

  it('cancel() stops the open session by id even with no local turn (after a page reload)', () => {
    const conv = useConversationStore()
    const sessions = useSessionsStore()
    sessions.projectId = 'p'
    sessions.selectedId = 's'
    sessions.draftMode = false

    // No turn was started in this (freshly loaded) client, yet the session is running server-side.
    conv.cancel()

    expect(api.stopTurn).toHaveBeenCalledWith('p', 's')
  })
})
