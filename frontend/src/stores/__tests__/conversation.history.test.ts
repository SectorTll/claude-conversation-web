import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage } from '@/types/models'
import { useConversationStore } from '../conversation'

// The store module imports the api client; none of its calls are exercised here.
vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {},
  api: {},
}))

/** A minimal on-disk-shaped message (mirrors what the backend serializes). */
function msg(role: 'user' | 'assistant' | 'tool' | 'system', text: string): ChatMessage {
  return {
    role,
    timestamp: null,
    text,
    thinking: null,
    tools: [],
    user: role === 'user',
    assistant: role === 'assistant',
    tool: role === 'tool',
    system: role === 'system',
    hasText: text.length > 0,
    hasThinking: false,
    hasTools: false,
    roleHeader: '',
    timeDisplay: '',
  }
}

describe('userPromptHistory (composer ↑-recall)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('returns only the user prompts, oldest→newest', () => {
    const conv = useConversationStore()
    conv.messages.push(
      msg('user', 'first'),
      msg('assistant', 'reply'),
      msg('tool', 'tool output'),
      msg('user', 'second'),
      msg('system', 'noise'),
    )
    expect(conv.userPromptHistory()).toEqual(['first', 'second'])
  })

  it('collapses adjacent duplicates but keeps non-adjacent repeats', () => {
    const conv = useConversationStore()
    conv.messages.push(
      msg('user', 'a'),
      msg('user', 'a'),
      msg('user', 'b'),
      msg('user', 'a'),
    )
    expect(conv.userPromptHistory()).toEqual(['a', 'b', 'a'])
  })

  it('trims whitespace and skips blank or text-less user messages', () => {
    const conv = useConversationStore()
    const imageOnly = msg('user', '') // e.g. an attachment-only message
    conv.messages.push(msg('user', '  hello  '), msg('user', '   '), imageOnly)
    expect(conv.userPromptHistory()).toEqual(['hello'])
  })

  it('is empty for an empty conversation', () => {
    expect(useConversationStore().userPromptHistory()).toEqual([])
  })
})
