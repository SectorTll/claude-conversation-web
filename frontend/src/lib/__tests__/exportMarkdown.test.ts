import { describe, expect, it } from 'vitest'
import type { ChatMessage, ToolBlock } from '@/types/models'
import { conversationToMarkdown, exportFileName } from '../exportMarkdown'

function msg(over: Partial<ChatMessage>): ChatMessage {
  return {
    role: 'assistant',
    timestamp: null,
    text: '',
    thinking: null,
    tools: [],
    user: false,
    assistant: true,
    tool: false,
    system: false,
    hasText: false,
    hasThinking: false,
    hasTools: false,
    roleHeader: 'Claude',
    timeDisplay: '12:00',
    ...over,
  }
}

describe('conversationToMarkdown', () => {
  it('renders title, role headers, text, thinking and tool blocks', () => {
    const tool: ToolBlock = { kind: 'use', title: 'Bash', body: 'ls -la' }
    const md = conversationToMarkdown('My session', [
      msg({ roleHeader: 'You', user: true, assistant: false, text: 'hello', hasText: true }),
      msg({ text: 'world', hasText: true, thinking: 'hmm', hasThinking: true, tools: [tool] }),
    ])
    expect(md).toContain('# My session')
    expect(md).toContain('## You · 12:00')
    expect(md).toContain('hello')
    expect(md).toContain('<details><summary>Thinking</summary>')
    expect(md).toContain('**🔧 Bash**')
    expect(md).toContain('```\nls -la\n```')
  })

  it('grows the code fence past any backtick run inside a tool body', () => {
    const tool: ToolBlock = { kind: 'result', title: 'Result', body: 'uses ```js fences' }
    const md = conversationToMarkdown('t', [msg({ tools: [tool] })])
    expect(md).toContain('````\nuses ```js fences\n````')
  })
})

describe('exportFileName', () => {
  it('sanitizes filesystem-hostile characters and caps the length', () => {
    expect(exportFileName('a/b:c*d?e')).toBe('a_b_c_d_e.md')
    expect(exportFileName('x'.repeat(100))).toBe(`${'x'.repeat(60)}.md`)
    expect(exportFileName('   ')).toBe('conversation.md')
  })
})
