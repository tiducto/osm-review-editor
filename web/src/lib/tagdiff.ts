import * as shared from 'osm-review-shared'
import type { TagDiff } from '@/generated/models'

type Tags = Record<string, string>

export function tagDiff(base: Tags, proposed: Tags): TagDiff {
  return shared.tagDiff(base, proposed) as TagDiff
}

export function applyTagEdit(base: Tags, set: Tags, remove: string[] = []): Tags {
  return shared.applyTagEdit(base, set, remove) as Tags
}

export function tagDiffSummary(base: Tags, proposed: Tags): string {
  return shared.tagDiffSummary(base, proposed)
}

export function isNoOp(d: TagDiff): boolean {
  return !Object.keys(d.added).length && !Object.keys(d.removed).length && !Object.keys(d.changed).length
}

export function diffSize(d: TagDiff): number {
  return Object.keys(d.added).length + Object.keys(d.removed).length + Object.keys(d.changed).length
}
