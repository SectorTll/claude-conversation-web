import { describe, expect, it } from 'vitest'
import type { SlashCommandInfo } from '@/types/models'
import { atFragment, filterCommands, filterFiles, slashFragment } from '../slashCommands'

function cmd(name: string, description = ''): SlashCommandInfo {
  return { name, description, argumentHint: null, source: 'user', kind: 'skill' }
}

describe('slashFragment (when the popup shows)', () => {
  it('matches "/" plus a partial name', () => {
    expect(slashFragment('/')).toBe('')
    expect(slashFragment('/rev')).toBe('rev')
    expect(slashFragment('/git:commit')).toBe('git:commit')
  })

  it('hides once arguments begin or on multi-line text', () => {
    expect(slashFragment('/review 42')).toBeNull()
    expect(slashFragment('/rev\nmore')).toBeNull()
  })

  it('never triggers without a leading slash', () => {
    expect(slashFragment('')).toBeNull()
    expect(slashFragment('see /review')).toBeNull()
    expect(slashFragment('x/')).toBeNull()
  })
})

describe('filterCommands (popup ranking)', () => {
  const ALL = [
    cmd('deploy', 'ship to prod'),
    cmd('git:commit'),
    cmd('review', 'look at a merge request'),
  ]

  it('returns everything for the bare slash', () => {
    expect(filterCommands(ALL, '')).toEqual(ALL)
  })

  it('ranks name-prefix matches before mid-name substring matches', () => {
    const names = filterCommands([cmd('git:commit'), cmd('compact')], 'com').map((c) => c.name)
    expect(names).toEqual(['compact', 'git:commit'])
  })

  it('matches the description too, case-insensitively', () => {
    const names = filterCommands(ALL, 'PROD').map((c) => c.name)
    expect(names).toEqual(['deploy'])
  })

  it('drops commands that match nowhere', () => {
    expect(filterCommands(ALL, 'zzz')).toEqual([])
  })
})

describe('atFragment (when the @-file popup shows)', () => {
  it('matches an @token at the caret, at start or after whitespace', () => {
    expect(atFragment('@')).toEqual({ fragment: '', start: 0 })
    expect(atFragment('see @src/ma')).toEqual({ fragment: 'src/ma', start: 4 })
    expect(atFragment('a\n@x')).toEqual({ fragment: 'x', start: 2 })
  })

  it('ignores emails and tokens not at the caret', () => {
    expect(atFragment('mail me a@b')).toBeNull()
    expect(atFragment('@file done ')).toBeNull()
  })
})

describe('filterFiles (@-popup ranking)', () => {
  const PATHS = ['README.md', 'src/app.ts', 'src/main/app.css', 'docs/apps.md']

  it('ranks basename prefix, then path prefix, then substring', () => {
    expect(filterFiles(PATHS, 'app')).toEqual([
      'src/app.ts', // basename prefix
      'src/main/app.css', // basename prefix (deeper — stable order)
      'docs/apps.md', // basename prefix too
    ])
    expect(filterFiles(PATHS, 'src')).toEqual(['src/app.ts', 'src/main/app.css'])
  })

  it('caps the result list', () => {
    const many = Array.from({ length: 50 }, (_, i) => `f${i}.txt`)
    expect(filterFiles(many, 'f', 20)).toHaveLength(20)
  })
})
