import { describe, expect, it } from 'vitest'
import type { Change, ChangeLink } from '@/generated/models'
import { isUntaggedNewNode, orphanRisk } from './changes'

function link(id: number, status: ChangeLink['status'], action: ChangeLink['action'] = 'create'): ChangeLink {
  return { changeId: `w${id}`, ref: { type: 'way', id }, action, status }
}

function node(newTags: Record<string, string>, requiredBy: ChangeLink[], action: Change['action'] = 'create'): Change {
  return { id: 'n', draftId: 'd', ref: { type: 'node', id: -1 }, action, newTags, requiredBy, dependsOn: [] } as unknown as Change
}

describe('orphan risk', () => {
  it('flags an untagged new node whose ways are not approved', () => {
    const risk = orphanRisk(node({}, [link(-3, 'pending'), link(4, 'rejected', 'modify')]))
    expect(risk?.pending.map((l) => l.ref.id)).toEqual([-3])
    expect(risk?.blocked.map((l) => l.ref.id)).toEqual([4])
  })

  it('is satisfied by one approved or uploaded way', () => {
    expect(orphanRisk(node({}, [link(-3, 'pending'), link(-4, 'approved')]))).toBeNull()
    expect(orphanRisk(node({}, [link(-3, 'uploaded')]))).toBeNull()
  })

  it('reports a node no way uses', () => {
    expect(orphanRisk(node({}, []))).toEqual({ pending: [], blocked: [] })
  })

  it('ignores tagged nodes, tags added in review and existing nodes', () => {
    expect(orphanRisk(node({ barrier: 'gate' }, [link(-3, 'pending')]))).toBeNull()
    expect(orphanRisk(node({}, [link(-3, 'pending')]), { barrier: 'gate' })).toBeNull()
    expect(isUntaggedNewNode(node({}, [], 'modify'))).toBe(false)
  })
})
