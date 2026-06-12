import { describe, expect, it } from 'vitest'
import { buildDiffRows, collapseRows } from '@/lib/diff'

describe('buildDiffRows', () => {
  it('marks added, removed and unchanged lines', () => {
    const rows = buildDiffRows('a\nb\nc\n', 'a\nB\nc\n')
    expect(rows).toEqual([
      { sign: ' ', text: 'a' },
      { sign: '-', text: 'b' },
      { sign: '+', text: 'B' },
      { sign: ' ', text: 'c' },
    ])
  })

  it('renders a Write (null oldText) as all additions', () => {
    const rows = buildDiffRows(null, 'one\ntwo')
    expect(rows).toEqual([
      { sign: '+', text: 'one' },
      { sign: '+', text: 'two' },
    ])
  })

  it('does not emit an empty trailing row for a trailing newline', () => {
    const rows = buildDiffRows(null, 'only\n')
    expect(rows).toEqual([{ sign: '+', text: 'only' }])
  })
})

describe('collapseRows', () => {
  it('keeps short context runs intact', () => {
    const rows = buildDiffRows('a\nb\nc\n', 'a\nb\nX\n')
    expect(collapseRows(rows, 3)).toEqual(rows)
  })

  it('folds long unchanged runs around a change', () => {
    const ctx = Array.from({ length: 20 }, (_, i) => `line${i}`).join('\n')
    const rows = buildDiffRows(`${ctx}\nold\n`, `${ctx}\nnew\n`)
    const folded = collapseRows(rows, 3)
    const fold = folded.find((r) => r.folded)
    expect(fold).toBeTruthy()
    expect(fold!.folded).toBe(20 - 3) // head of file: nothing kept above the fold, 3 kept below
    expect(folded.filter((r) => r.sign === '-')).toHaveLength(1)
    expect(folded.filter((r) => r.sign === '+')).toHaveLength(1)
    expect(folded.length).toBeLessThan(rows.length)
  })
})
