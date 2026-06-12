import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useSessionsStore } from '../sessions'
import { useProjectsStore } from '../projects'
import type { ProjectInfo, SessionInfo } from '@/types/models'

function session(id: string, over: Partial<SessionInfo> = {}): SessionInfo {
  return { sessionId: id, liveStatus: 'NONE', ...over } as unknown as SessionInfo
}
function project(id: string, over: Partial<ProjectInfo> = {}): ProjectInfo {
  return { projectId: id, liveStatus: 'NONE', ...over } as unknown as ProjectInfo
}

describe('sessions.mergeFrom (selection-preserving)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('carries live status forward and keeps selection', () => {
    const s = useSessionsStore()
    s.items = [session('a', { liveStatus: 'WORKING' }), session('b')]
    s.selectedId = 'a'

    // 'b' removed on disk, 'c' added, 'a' re-scanned (status reset to NONE in the payload)
    s.mergeFrom([session('a'), session('c')])

    expect(s.items.map((x) => x.sessionId)).toEqual(['a', 'c'])
    expect(s.items.find((x) => x.sessionId === 'a')!.liveStatus).toBe('WORKING')
    expect(s.selectedId).toBe('a')
  })

  it('carries the neutral IDLE status (open terminal) forward like any other', () => {
    const s = useSessionsStore()
    s.items = [session('a', { liveStatus: 'IDLE' })]

    s.mergeFrom([session('a')])

    expect(s.items.find((x) => x.sessionId === 'a')!.liveStatus).toBe('IDLE')
  })

  it('retains the selected session even if it vanished from disk', () => {
    const s = useSessionsStore()
    s.items = [session('a'), session('b')]
    s.selectedId = 'b'

    s.mergeFrom([session('a')])

    const ids = s.items.map((x) => x.sessionId)
    expect(ids).toContain('a')
    expect(ids).toContain('b') // kept because it is selected
    expect(s.selectedId).toBe('b')
  })
})

describe('projects.mergeFrom', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('keeps live status and selection across a refresh', () => {
    const p = useProjectsStore()
    p.items = [project('p1', { liveStatus: 'WAITING' }), project('p2')]
    p.selectedId = 'p1'

    p.mergeFrom([project('p1'), project('p2'), project('p3')])

    expect(p.items.map((x) => x.projectId)).toEqual(['p1', 'p2', 'p3'])
    expect(p.items.find((x) => x.projectId === 'p1')!.liveStatus).toBe('WAITING')
    expect(p.selectedId).toBe('p1')
  })
})
