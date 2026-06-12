import type { SlashCommandInfo } from '@/types/models'

/**
 * The command fragment being typed, or null when the popup should be hidden. A slash command is
 * the WHOLE message, so the popup only shows while the text is "/" plus a partial name — the first
 * space (arguments begin) or a newline ends the assist, and a slash mid-text never triggers it.
 */
export function slashFragment(text: string): string | null {
  const m = /^\/(\S*)$/.exec(text)
  return m ? m[1] : null
}

/**
 * The @-mention fragment at the caret, or null when the file popup should be hidden. Unlike a
 * slash command, an @-mention can sit anywhere in the message — but must follow start-of-text or
 * whitespace ("a@b" is an email, not a mention).
 */
export function atFragment(textUptoCaret: string): { fragment: string; start: number } | null {
  const m = /(?:^|\s)@([^\s@]*)$/.exec(textUptoCaret)
  if (!m) {
    return null
  }
  return { fragment: m[1], start: textUptoCaret.length - m[1].length - 1 }
}

/** Filter + rank file paths for the @-popup: basename prefix, then path prefix, then substring. */
export function filterFiles(paths: string[], fragment: string, limit = 20): string[] {
  const f = fragment.toLowerCase()
  const ranked: { p: string; rank: number }[] = []
  for (const p of paths) {
    const lower = p.toLowerCase()
    let rank: number
    if (!f) {
      rank = 2
    } else if (lower.slice(lower.lastIndexOf('/') + 1).startsWith(f)) {
      rank = 0
    } else if (lower.startsWith(f)) {
      rank = 1
    } else if (lower.includes(f)) {
      rank = 2
    } else {
      continue
    }
    ranked.push({ p, rank })
  }
  ranked.sort((a, b) => a.rank - b.rank) // stable: keeps the shallow-first server order per rank
  return ranked.slice(0, limit).map((x) => x.p)
}

/** Filter + rank for the popup: name-prefix matches first, then name/description substrings. */
export function filterCommands(all: SlashCommandInfo[], fragment: string): SlashCommandInfo[] {
  const f = fragment.toLowerCase()
  if (!f) {
    return all
  }
  const prefix: SlashCommandInfo[] = []
  const rest: SlashCommandInfo[] = []
  for (const c of all) {
    const name = c.name.toLowerCase()
    if (name.startsWith(f)) {
      prefix.push(c)
    } else if (name.includes(f) || c.description.toLowerCase().includes(f)) {
      rest.push(c)
    }
  }
  return prefix.concat(rest)
}
