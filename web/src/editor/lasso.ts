import type { IdAdapter } from './id/adapter'
import type { IdNode, LngLatTuple } from './types'

export const LASSO_MAX = 1000

function inside(p: LngLatTuple, ring: LngLatTuple[]): boolean {
  let hit = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]
    const [xj, yj] = ring[j]
    if (yi > p[1] !== yj > p[1] && p[0] < ((xj - xi) * (p[1] - yi)) / (yj - yi) + xi) hit = !hit
  }
  return hit
}

export function lassoNodes(adapter: IdAdapter, polygon: LngLatTuple[]): string[] {
  if (polygon.length < 3) return []
  let w = Infinity
  let s = Infinity
  let e = -Infinity
  let n = -Infinity
  for (const [x, y] of polygon) {
    w = Math.min(w, x)
    e = Math.max(e, x)
    s = Math.min(s, y)
    n = Math.max(n, y)
  }
  const out: string[] = []
  for (const ent of adapter.intersects([
    [w, s],
    [e, n],
  ])) {
    if (ent.type !== 'node') continue
    if (!adapter.isLoaded((ent as IdNode).loc)) continue
    if (inside((ent as IdNode).loc, polygon)) out.push(ent.id)
    if (out.length >= LASSO_MAX) break
  }
  return out.sort()
}
