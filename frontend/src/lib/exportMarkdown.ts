import type { ChatMessage } from '@/types/models'

/**
 * Render the loaded conversation as a standalone Markdown document (the ⤓ Export button).
 * Message text is already markdown and is embedded as-is; thinking goes into a collapsed
 * <details>; tool blocks become fenced blocks so their content never breaks the document.
 */
export function conversationToMarkdown(title: string, messages: ChatMessage[]): string {
  const out: string[] = [`# ${title}`, '']
  for (const m of messages) {
    out.push(`## ${m.roleHeader}${m.timeDisplay ? ` · ${m.timeDisplay}` : ''}`, '')
    if (m.hasThinking && m.thinking) {
      out.push('<details><summary>Thinking</summary>', '', m.thinking, '', '</details>', '')
    }
    if (m.hasText) {
      out.push(m.text, '')
    }
    for (const t of m.tools) {
      out.push(`**${t.kind === 'use' ? '🔧' : '⤷'} ${t.title}**${t.filePath ? ` — \`${t.filePath}\`` : ''}`, '')
      if (t.body) {
        out.push(fence(t.body), '')
      }
    }
  }
  return out.join('\n')
}

/** A code fence guaranteed to be longer than any backtick run inside the body. */
function fence(body: string): string {
  const longest = body.match(/`+/g)?.reduce((a, b) => (b.length > a.length ? b : a), '') ?? ''
  const marks = '`'.repeat(Math.max(3, longest.length + 1))
  return `${marks}\n${body}\n${marks}`
}

/** A filesystem-safe download name for the exported conversation. */
export function exportFileName(title: string): string {
  const safe = title.replace(/[\\/:*?"<>|]/g, '_').trim().slice(0, 60)
  return `${safe || 'conversation'}.md`
}
