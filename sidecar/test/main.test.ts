// Unit tests for the stdio router + canUseTool bridge with a fake `query` — no real CLI involved.
import { describe, expect, it, vi } from 'vitest'
import { createSidecar, type SidecarIo } from '../src/main.js'
import type { OutMessage } from '../src/protocol.js'

interface FakeQuery {
  pushed: unknown[]
  interrupt: ReturnType<typeof vi.fn>
  setPermissionMode: ReturnType<typeof vi.fn>
  setModel: ReturnType<typeof vi.fn>
  emit(msg: unknown): void
  end(): void
  [Symbol.asyncIterator](): AsyncIterator<unknown>
}

function fakeQueryFactory() {
  const captured: { prompt?: AsyncIterable<unknown>; options?: any } = {}
  const queue: unknown[] = []
  let notify: (() => void) | null = null
  let ended = false
  const q: FakeQuery = {
    pushed: [],
    interrupt: vi.fn(async () => {}),
    setPermissionMode: vi.fn(async () => {}),
    setModel: vi.fn(async () => {}),
    emit(msg: unknown) {
      queue.push(msg)
      notify?.()
      notify = null
    },
    end() {
      ended = true
      notify?.()
      notify = null
    },
    async *[Symbol.asyncIterator]() {
      while (true) {
        while (queue.length > 0) yield queue.shift()!
        if (ended) return
        await new Promise<void>((r) => (notify = r))
      }
    },
  }
  const factory = (args: { prompt: AsyncIterable<unknown>; options: any }) => {
    captured.prompt = args.prompt
    captured.options = args.options
    // drain the prompt iterable in the background like the real SDK does
    void (async () => {
      for await (const m of args.prompt) q.pushed.push(m)
    })()
    return q as any
  }
  return { q, factory, captured }
}

function io() {
  const events: OutMessage[] = []
  const exits: number[] = []
  const sio: SidecarIo = {
    emit: (m) => events.push(m),
    exit: (c) => exits.push(c),
  }
  return { events, exits, sio }
}

const tick = () => new Promise((r) => setTimeout(r))

const INIT = {
  type: 'init' as const,
  sessionId: 'sid-1',
  resume: false,
  cwd: 'C:/work',
  permissionMode: 'default',
  executable: 'claude',
}

describe('sidecar bridge', () => {
  it('init builds the query with CLI-parity options and ready is emitted from system:init', async () => {
    const { q, factory, captured } = fakeQueryFactory()
    const { events, sio } = io()
    const sidecar = createSidecar(sio, factory as any)

    await sidecar.handle(INIT)
    expect(captured.options).toMatchObject({
      cwd: 'C:/work',
      permissionMode: 'default',
      includePartialMessages: true,
      settingSources: ['user', 'project', 'local'],
      systemPrompt: { type: 'preset', preset: 'claude_code' },
      pathToClaudeCodeExecutable: 'claude',
      extraArgs: { 'session-id': 'sid-1' },
    })

    q.emit({ type: 'system', subtype: 'init', session_id: 'actual-id' })
    await tick()
    const ready = events.find((e) => e.type === 'ready') as any
    expect(ready).toMatchObject({ sessionId: 'actual-id' })
    expect(typeof ready.sdkVersion).toBe('string')
  })

  it('resume sessions pass options.resume instead of a session-id arg', async () => {
    const { factory, captured } = fakeQueryFactory()
    const sidecar = createSidecar(io().sio, factory as any)
    await sidecar.handle({ ...INIT, resume: true })
    expect(captured.options.resume).toBe('sid-1')
    expect(captured.options.extraArgs).toBeUndefined()
  })

  it('user_turn pushes the message into the prompt stream and emits turn_started', async () => {
    const { q, factory } = fakeQueryFactory()
    const { events, sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)

    await sidecar.handle({ type: 'user_turn', turnId: 't1', text: 'hello' })
    await tick()
    expect(events).toContainEqual({ type: 'turn_started', turnId: 't1' })
    expect(q.pushed).toContainEqual({
      type: 'user',
      message: { role: 'user', content: 'hello' },
      parent_tool_use_id: null,
    })
  })

  it('user_turn with images pushes a content-block list (text first, then base64 images)', async () => {
    const { q, factory } = fakeQueryFactory()
    const sidecar = createSidecar(io().sio, factory as any)
    await sidecar.handle(INIT)

    await sidecar.handle({
      type: 'user_turn',
      turnId: 't1',
      text: 'what is this?',
      images: [{ mediaType: 'image/png', data: 'aWJt' }],
    })
    await tick()
    expect(q.pushed).toContainEqual({
      type: 'user',
      message: {
        role: 'user',
        content: [
          { type: 'text', text: 'what is this?' },
          { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'aWJt' } },
        ],
      },
      parent_tool_use_id: null,
    })
  })

  it('user_turn with images and no text omits the empty text block', async () => {
    const { q, factory } = fakeQueryFactory()
    const sidecar = createSidecar(io().sio, factory as any)
    await sidecar.handle(INIT)

    await sidecar.handle({
      type: 'user_turn',
      turnId: 't1',
      text: '',
      images: [{ mediaType: 'image/jpeg', data: 'aWJt' }],
    })
    await tick()
    const pushed = q.pushed[0] as any
    expect(pushed.message.content).toEqual([
      { type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: 'aWJt' } },
    ])
  })

  it('a mode change on user_turn goes through setPermissionMode once', async () => {
    const { q, factory } = fakeQueryFactory()
    const sidecar = createSidecar(io().sio, factory as any)
    await sidecar.handle(INIT)

    await sidecar.handle({ type: 'user_turn', turnId: 't1', text: 'a', permissionMode: 'default' })
    expect(q.setPermissionMode).not.toHaveBeenCalled() // same as init mode

    await sidecar.handle({ type: 'user_turn', turnId: 't2', text: 'b', permissionMode: 'acceptEdits' })
    expect(q.setPermissionMode).toHaveBeenCalledWith('acceptEdits')
  })

  it('a refused mode switch fails the turn but keeps the sidecar alive and retries next turn', async () => {
    const { q, factory } = fakeQueryFactory()
    const { events, exits, sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)

    q.setPermissionMode.mockRejectedValueOnce(new Error('not launched with --dangerously-skip-permissions'))
    await sidecar.handle({ type: 'user_turn', turnId: 't1', text: 'a', permissionMode: 'bypassPermissions' })
    await tick()
    expect(events).toContainEqual({
      type: 'turn_error',
      turnId: 't1',
      message: 'not launched with --dangerously-skip-permissions',
    })
    expect(exits).toHaveLength(0) // no fatal — the process stays up
    expect(q.pushed).toHaveLength(0) // the prompt was not sent in the wrong mode

    // The failed switch was not recorded — the next turn retries it (and may succeed now).
    await sidecar.handle({ type: 'user_turn', turnId: 't2', text: 'b', permissionMode: 'bypassPermissions' })
    await tick()
    expect(q.setPermissionMode).toHaveBeenCalledTimes(2)
    expect(events).toContainEqual({ type: 'turn_started', turnId: 't2' })
  })

  it('a model change on user_turn goes through setModel; clearing it resets to the default', async () => {
    const { q, factory } = fakeQueryFactory()
    const sidecar = createSidecar(io().sio, factory as any)
    await sidecar.handle(INIT)

    await sidecar.handle({ type: 'user_turn', turnId: 't1', text: 'a' })
    expect(q.setModel).not.toHaveBeenCalled() // default model from the start — nothing to change

    await sidecar.handle({ type: 'user_turn', turnId: 't2', text: 'b', model: 'haiku' })
    expect(q.setModel).toHaveBeenCalledWith('haiku')

    await sidecar.handle({ type: 'user_turn', turnId: 't3', text: 'c' })
    expect(q.setModel).toHaveBeenLastCalledWith(undefined) // back to the user's default
  })

  it('canUseTool bridges a tool to permission_request and resolves on permission_response', async () => {
    const { factory, captured } = fakeQueryFactory()
    const { events, sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)
    await sidecar.handle({ type: 'user_turn', turnId: 't1', text: 'go' })

    const resultP = captured.options.canUseTool('Bash', { command: 'rm -rf' }, {
      signal: new AbortController().signal,
    })
    await tick()
    const req = events.find((e) => e.type === 'permission_request') as any
    expect(req).toMatchObject({ turnId: 't1', toolName: 'Bash', input: { command: 'rm -rf' } })

    await sidecar.handle({ type: 'permission_response', requestId: req.requestId, behavior: 'deny', message: 'no' })
    await expect(resultP).resolves.toEqual({ behavior: 'deny', message: 'no' })
  })

  it('allow passes the original input back as updatedInput', async () => {
    const { factory, captured } = fakeQueryFactory()
    const { events, sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)

    const input = { file_path: 'x.txt', content: 'hi' }
    const resultP = captured.options.canUseTool('Write', input, { signal: new AbortController().signal })
    await tick()
    const req = events.find((e) => e.type === 'permission_request') as any

    await sidecar.handle({ type: 'permission_response', requestId: req.requestId, behavior: 'allow' })
    await expect(resultP).resolves.toEqual({ behavior: 'allow', updatedInput: input })
  })

  it('always-allow returns the SDK suggestions as updatedPermissions', async () => {
    const { factory, captured } = fakeQueryFactory()
    const { events, sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)

    const suggestions = [{ type: 'addRules', rules: [{ toolName: 'Bash' }] }]
    const resultP = captured.options.canUseTool('Bash', { command: 'ls' }, {
      signal: new AbortController().signal,
      suggestions,
    })
    await tick()
    const req = events.find((e) => e.type === 'permission_request') as any
    expect(req.suggestions).toEqual(suggestions)

    await sidecar.handle({ type: 'permission_response', requestId: req.requestId, behavior: 'allow', always: true })
    await expect(resultP).resolves.toEqual({
      behavior: 'allow',
      updatedInput: { command: 'ls' },
      updatedPermissions: suggestions,
    })
  })

  it('thinking deltas and tool results are forwarded', async () => {
    const { q, factory } = fakeQueryFactory()
    const { events, sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)
    await sidecar.handle({ type: 'user_turn', turnId: 't1', text: 'go' })

    q.emit({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'thinking_delta', thinking: 'hmm' } } })
    q.emit({ type: 'user', message: { content: [
      { type: 'tool_result', tool_use_id: 'tu1', content: [{ type: 'text', text: 'file contents' }] },
    ] } })
    await tick()

    expect(events).toContainEqual({ type: 'thinking_delta', turnId: 't1', text: 'hmm' })
    expect(events).toContainEqual({ type: 'tool_result', turnId: 't1', toolUseId: 'tu1', body: 'file contents' })
  })

  it('AskUserQuestion becomes a question message and the answer lands in updatedInput.answers', async () => {
    const { factory, captured } = fakeQueryFactory()
    const { events, sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)
    await sidecar.handle({ type: 'user_turn', turnId: 't1', text: 'go' })

    const questions = [
      { question: 'Which color?', header: 'Color', multiSelect: false, options: [{ label: 'Red', description: '' }, { label: 'Blue', description: '' }] },
    ]
    const resultP = captured.options.canUseTool('AskUserQuestion', { questions }, {
      signal: new AbortController().signal,
    })
    await tick()
    const qe = events.find((e) => e.type === 'question') as any
    expect(qe).toMatchObject({ turnId: 't1', questions })
    expect(events.some((e) => e.type === 'permission_request')).toBe(false)

    await sidecar.handle({ type: 'question_answer', requestId: qe.requestId, answers: [['Blue']] })
    await expect(resultP).resolves.toEqual({
      behavior: 'allow',
      updatedInput: { questions, answers: { 'Which color?': 'Blue' } },
    })
  })

  it('result messages map to turn_done / turn_error and stream deltas to text_delta/tool', async () => {
    const { q, factory } = fakeQueryFactory()
    const { events, sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)
    await sidecar.handle({ type: 'user_turn', turnId: 't1', text: 'go' })

    q.emit({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hi' } } })
    q.emit({ type: 'stream_event', event: { type: 'content_block_start', content_block: { type: 'tool_use', id: 'tu1', name: 'Read' } } })
    // AskUserQuestion tool blocks are suppressed — they surface via the question card instead
    q.emit({ type: 'stream_event', event: { type: 'content_block_start', content_block: { type: 'tool_use', id: 'tu2', name: 'AskUserQuestion' } } })
    q.emit({ type: 'result', subtype: 'success' })
    await tick()

    expect(events).toContainEqual({ type: 'text_delta', turnId: 't1', text: 'hi' })
    expect(events).toContainEqual({ type: 'tool', turnId: 't1', toolUseId: 'tu1', title: 'Read' })
    expect(events.filter((e) => e.type === 'tool')).toHaveLength(1)
    expect(events).toContainEqual({ type: 'turn_done', turnId: 't1' })

    q.emit({ type: 'result', subtype: 'error_during_execution' })
    await tick()
    expect(events).toContainEqual({ type: 'turn_error', turnId: 't1', message: 'error_during_execution' })
  })

  it('interrupt denies pending asks before interrupting the query', async () => {
    const { q, factory, captured } = fakeQueryFactory()
    const { sio } = io()
    const sidecar = createSidecar(sio, factory as any)
    await sidecar.handle(INIT)

    const resultP = captured.options.canUseTool('Bash', {}, { signal: new AbortController().signal })
    await tick()

    await sidecar.handle({ type: 'interrupt' })
    await expect(resultP).resolves.toMatchObject({ behavior: 'deny', message: 'turn interrupted' })
    expect(q.interrupt).toHaveBeenCalled()
  })
})
