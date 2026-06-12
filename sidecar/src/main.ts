// Chat sidecar: holds ONE persistent Claude Agent SDK session and bridges it to the Spring
// backend over an NDJSON stdio protocol (see protocol.ts). The Java side owns all timeouts and
// kills this process for hard cancellation; this side keeps the session warm across turns and
// routes tool permissions + AskUserQuestion to the browser via canUseTool.
import { query, type Options, type PermissionResult, type PermissionUpdate, type Query, type SDKUserMessage } from '@anthropic-ai/claude-agent-sdk'
import { createInterface } from 'node:readline'
import { randomUUID } from 'node:crypto'
import type { InMessage, OutMessage, QuestionSpec } from './protocol.js'

/** The single CLI tool surfaced as an interactive question instead of a permission card. */
const QUESTION_TOOL = 'AskUserQuestion'

type QueryFactory = typeof query

export interface SidecarIo {
  emit(msg: OutMessage): void
  exit(code: number): void
}

interface Pending {
  resolve(result: PermissionResult): void
  toolName: string
  input: Record<string, unknown>
  /** The SDK's "don't ask again" rule suggestions — persisted when the user picks Always allow. */
  suggestions: PermissionUpdate[]
}

/**
 * Wires the stdio protocol to one SDK session. Exported (with an injectable query factory) so the
 * routing/bridging logic is unit-testable without a real CLI.
 */
export function createSidecar(io: SidecarIo, queryFn: QueryFactory = query) {
  let q: Query | null = null
  let consumeDone: Promise<void> | null = null
  let currentTurnId = ''
  let currentMode = ''
  let currentModel = ''
  let ready = false
  const pending = new Map<string, Pending>()

  // Pushable async iterable: user turns are appended over time, ended on shutdown.
  const queue: SDKUserMessage[] = []
  let notify: (() => void) | null = null
  let inputClosed = false
  async function* input(): AsyncIterable<SDKUserMessage> {
    while (true) {
      while (queue.length > 0) yield queue.shift()!
      if (inputClosed) return
      await new Promise<void>((r) => (notify = r))
    }
  }
  const wake = () => {
    notify?.()
    notify = null
  }

  function canUseTool(
    toolName: string,
    input: Record<string, unknown>,
    opts: { signal: AbortSignal; suggestions?: PermissionUpdate[] },
  ): Promise<PermissionResult> {
    return new Promise<PermissionResult>((resolve) => {
      const requestId = randomUUID()
      const suggestions = opts.suggestions ?? []
      pending.set(requestId, { resolve, toolName, input, suggestions })
      opts.signal.addEventListener('abort', () => {
        if (pending.delete(requestId)) {
          resolve({ behavior: 'deny', message: 'turn interrupted' })
        }
      })
      if (toolName === QUESTION_TOOL) {
        io.emit({
          type: 'question',
          requestId,
          turnId: currentTurnId,
          questions: (input.questions as QuestionSpec[]) ?? [],
        })
      } else {
        io.emit({ type: 'permission_request', requestId, turnId: currentTurnId, toolName, input, suggestions })
      }
    })
  }

  /** Flatten a tool_result's content (string or content-block list) into preview text. */
  function toolResultBody(content: unknown): string {
    if (typeof content === 'string') return content
    if (Array.isArray(content)) {
      return content
        .map((b: any) => (b?.type === 'text' && typeof b.text === 'string' ? b.text : ''))
        .filter(Boolean)
        .join('\n')
    }
    return ''
  }

  async function consume(qy: Query) {
    for await (const msg of qy) {
      if (msg.type === 'system' && msg.subtype === 'init') {
        if (!ready) {
          ready = true
          io.emit({
            type: 'ready',
            sessionId: msg.session_id,
            sdkVersion: process.env.CLAUDE_AGENT_SDK_VERSION ?? 'unknown',
          })
        }
      } else if (msg.type === 'stream_event') {
        const e = msg.event as any
        if (e.type === 'content_block_delta' && e.delta?.type === 'text_delta' && e.delta.text) {
          io.emit({ type: 'text_delta', turnId: currentTurnId, text: e.delta.text })
        } else if (e.type === 'content_block_delta' && e.delta?.type === 'thinking_delta' && e.delta.thinking) {
          io.emit({ type: 'thinking_delta', turnId: currentTurnId, text: e.delta.thinking })
        } else if (e.type === 'content_block_start' && e.content_block?.type === 'tool_use') {
          // AskUserQuestion surfaces via canUseTool as an interactive question, not a tool block.
          if (e.content_block.name !== QUESTION_TOOL) {
            io.emit({
              type: 'tool',
              turnId: currentTurnId,
              toolUseId: e.content_block.id ?? '',
              title: e.content_block.name ?? 'tool',
            })
          }
        }
      } else if (msg.type === 'user') {
        // Tool results echo back as user-role messages; surface their bodies for the live view.
        const content = (msg as any).message?.content
        if (Array.isArray(content)) {
          for (const block of content) {
            if (block?.type === 'tool_result' && block.tool_use_id) {
              const body = toolResultBody(block.content).slice(0, 4000)
              if (body) {
                io.emit({ type: 'tool_result', turnId: currentTurnId, toolUseId: block.tool_use_id, body })
              }
            }
          }
        }
      } else if (msg.type === 'result') {
        if (msg.subtype === 'success') {
          io.emit({ type: 'turn_done', turnId: currentTurnId })
        } else {
          const detail = 'result' in msg && typeof msg.result === 'string' ? msg.result : msg.subtype
          io.emit({ type: 'turn_error', turnId: currentTurnId, message: detail })
        }
      }
    }
  }

  /** Resolve a pending canUseTool bridge; unknown/duplicate ids are ignored (Java logs them). */
  function settle(requestId: string, result: PermissionResult) {
    const p = pending.get(requestId)
    if (p) {
      pending.delete(requestId)
      p.resolve(result)
    }
  }

  async function handle(msg: InMessage): Promise<void> {
    switch (msg.type) {
      case 'init': {
        if (q) return // double init — ignore
        currentMode = msg.permissionMode
        const options: Options = {
          cwd: msg.cwd,
          permissionMode: msg.permissionMode as Options['permissionMode'],
          includePartialMessages: true,
          // Match the interactive CLI: load CLAUDE.md + settings, use the claude_code preset,
          // and drive the SYSTEM claude executable so versions match terminal sessions.
          settingSources: ['user', 'project', 'local'],
          systemPrompt: { type: 'preset', preset: 'claude_code' },
          pathToClaudeCodeExecutable: msg.executable,
          canUseTool,
          stderr: (line: string) => console.error('[claude]', line),
          ...(msg.resume ? { resume: msg.sessionId } : { extraArgs: { 'session-id': msg.sessionId } }),
        }
        q = queryFn({ prompt: input(), options })
        consumeDone = consume(q).catch((e) => {
          io.emit({ type: 'fatal', message: String(e?.message ?? e) })
          io.exit(1)
        })
        return
      }
      case 'user_turn': {
        if (!q) throw new Error('user_turn before init')
        currentTurnId = msg.turnId
        try {
          // Record the new mode/model only AFTER the SDK accepted it — a refused switch must be
          // retried on the next turn, not silently considered applied.
          if (msg.permissionMode && msg.permissionMode !== currentMode) {
            await q.setPermissionMode(msg.permissionMode as Options['permissionMode'] & string)
            currentMode = msg.permissionMode
          }
          if ((msg.model ?? '') !== currentModel) {
            await q.setModel((msg.model ?? '') || undefined)
            currentModel = msg.model ?? ''
          }
        } catch (e: any) {
          // E.g. the CLI refuses setPermissionMode(bypassPermissions) on a process not launched
          // with --dangerously-skip-permissions. Fail THIS turn (Java shows an error bubble and
          // keeps the warm session) instead of taking the whole sidecar down.
          io.emit({ type: 'turn_error', turnId: msg.turnId, message: String(e?.message ?? e) })
          return
        }
        io.emit({ type: 'turn_started', turnId: msg.turnId })
        // Images turn the content into a block list; a text-only turn keeps the plain-string shape
        // (byte-identical wire behavior to before images existed).
        const content = msg.images?.length
          ? [
              ...(msg.text ? [{ type: 'text' as const, text: msg.text }] : []),
              ...msg.images.map((img) => ({
                type: 'image' as const,
                source: {
                  type: 'base64' as const,
                  media_type: img.mediaType as 'image/png' | 'image/jpeg' | 'image/gif' | 'image/webp',
                  data: img.data,
                },
              })),
            ]
          : msg.text
        queue.push({ type: 'user', message: { role: 'user', content }, parent_tool_use_id: null })
        wake()
        return
      }
      case 'permission_response': {
        const p = pending.get(msg.requestId)
        settle(
          msg.requestId,
          msg.behavior === 'allow'
            ? {
                behavior: 'allow',
                updatedInput: p?.input,
                // Always allow: persist the SDK's suggested rules so this tool stops asking.
                ...(msg.always && p && p.suggestions.length > 0 ? { updatedPermissions: p.suggestions } : {}),
              }
            : { behavior: 'deny', message: msg.message ?? 'denied in browser' },
        )
        return
      }
      case 'question_answer': {
        const p = pending.get(msg.requestId)
        if (!p) return
        // Map per-question selections back to the {question text -> joined labels} shape the
        // CLI expects in AskUserQuestion's updatedInput (verified in the migration spike).
        const questions = (p.input.questions as QuestionSpec[]) ?? []
        const answers: Record<string, string> = {}
        questions.forEach((qq, i) => {
          answers[qq.question] = (msg.answers[i] ?? []).join(', ')
        })
        settle(msg.requestId, { behavior: 'allow', updatedInput: { ...p.input, answers } })
        return
      }
      case 'interrupt': {
        // Deny pending bridges first so canUseTool never dangles, then stop the turn.
        for (const id of [...pending.keys()]) {
          settle(id, { behavior: 'deny', message: 'turn interrupted', interrupt: true })
        }
        await q?.interrupt().catch(() => undefined)
        return
      }
      case 'shutdown': {
        inputClosed = true
        wake()
        await q?.interrupt().catch(() => undefined)
        await consumeDone?.catch(() => undefined)
        io.exit(0)
        return
      }
    }
  }

  return { handle }
}

// ----------------------------------------------------------------- bootstrap (real stdio)
export function main() {
  const io: SidecarIo = {
    emit: (msg) => process.stdout.write(JSON.stringify(msg) + '\n'),
    exit: (code) => process.exit(code),
  }
  const sidecar = createSidecar(io)
  const rl = createInterface({ input: process.stdin, crlfDelay: Infinity })
  rl.on('line', (line) => {
    const trimmed = line.trim()
    if (!trimmed) return
    let msg: InMessage
    try {
      msg = JSON.parse(trimmed)
    } catch {
      console.error('[sidecar] unparseable line:', trimmed.slice(0, 200))
      return
    }
    sidecar.handle(msg).catch((e) => {
      io.emit({ type: 'fatal', message: String(e?.message ?? e) })
      io.exit(1)
    })
  })
  // EOF on stdin = the parent is gone (or asked us to stop): exit rather than linger orphaned.
  rl.on('close', () => {
    sidecar.handle({ type: 'shutdown' }).catch(() => process.exit(0))
  })
}

// Only boot when executed as a program (the bundle), not when imported by tests.
if (process.argv[1] && /sidecar\.mjs$|main\.(ts|js|mjs)$/.test(process.argv[1])) {
  main()
}
