// Line-diff model for Edit/Write tool blocks. Pure functions (unit-tested without DOM).
import { diffLines } from 'diff'

export interface DiffRow {
  sign: '+' | '-' | ' '
  text: string
}

/**
 * Line rows of an old→new change. `oldText === null` means a pure addition (Write): every line of
 * `newText` comes back as `+`. Trailing newlines don't produce an empty last row.
 */
export function buildDiffRows(oldText: string | null, newText: string | null): DiffRow[] {
  const oldS = oldText ?? ''
  const newS = newText ?? ''
  const rows: DiffRow[] = []
  for (const part of diffLines(oldS, newS)) {
    const sign: DiffRow['sign'] = part.added ? '+' : part.removed ? '-' : ' '
    for (const line of splitLines(part.value)) {
      rows.push({ sign, text: line })
    }
  }
  return rows
}

/**
 * Fold long unchanged runs: keep `maxContext` lines around each change, replace the middle of a
 * longer run with a single fold marker row carrying the hidden count.
 */
export interface FoldedRow extends DiffRow {
  // > 0 marks a fold row: `text` is empty and this many context lines are hidden behind it.
  folded?: number
}

export function collapseRows(rows: DiffRow[], maxContext = 3): FoldedRow[] {
  const out: FoldedRow[] = []
  let run: DiffRow[] = []

  const flush = (isEdge: boolean) => {
    // Around a change keep maxContext on both sides; at the start/end of the file one side suffices.
    const keep = maxContext
    if (run.length <= keep * 2 || (isEdge && run.length <= keep)) {
      out.push(...run)
    } else {
      const head = out.length === 0 ? 0 : keep // nothing above the first fold
      const tail = isEdge ? 0 : keep
      const hidden = run.length - head - tail
      if (hidden <= 1) {
        out.push(...run)
      } else {
        out.push(...run.slice(0, head))
        out.push({ sign: ' ', text: '', folded: hidden })
        if (tail > 0) {
          out.push(...run.slice(run.length - tail))
        }
      }
    }
    run = []
  }

  for (const row of rows) {
    if (row.sign === ' ') {
      run.push(row)
    } else {
      flush(false)
      out.push(row)
    }
  }
  flush(true)
  return out
}

function splitLines(value: string): string[] {
  const lines = value.split('\n')
  if (lines.length > 0 && lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}
