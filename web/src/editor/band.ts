import type { Position } from 'geojson'
import { ClipType, Clipper, EndType, FillRule, JoinType, PolyTreeD, type PathD, type PathsD, type PolyPathD } from 'clipper2-ts'

export const BAND_PX = 30
const MITER_LIMIT = 3
const PRECISION = 2

function toWorld(p: Position, scale: number): { x: number; y: number } {
  const x = ((p[0] + 180) / 360) * scale
  const s = Math.sin((p[1] * Math.PI) / 180)
  const y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * scale
  return { x, y }
}

function toLngLat(p: { x: number; y: number }, scale: number): Position {
  const lon = (p.x / scale) * 360 - 180
  const n = Math.PI - (2 * Math.PI * p.y) / scale
  const lat = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)))
  return [lon, lat]
}

function ring(path: PathD, scale: number): Position[] {
  const out = path.map((p) => toLngLat(p, scale))
  if (out.length) out.push(out[0])
  return out
}

function collect(node: PolyPathD, scale: number, out: Position[][][]) {
  for (let i = 0; i < node.count; i++) {
    const outer = node.child(i)
    if (!outer.poly || outer.poly.length < 3) continue
    const rings: Position[][] = [ring(outer.poly, scale)]
    for (let j = 0; j < outer.count; j++) {
      const hole = outer.child(j)
      if (hole.poly && hole.poly.length >= 3) rings.push(ring(hole.poly, scale))
      collect(hole, scale, out)
    }
    out.push(rings)
  }
}

export function bandPolygons(polys: Position[][][], zoom: number): Position[][][] {
  const scale = 512 * 2 ** zoom
  const source: PathsD = []
  for (const poly of polys) {
    poly.forEach((r, i) => {
      const pts = r.slice(0, r.length > 1 && r[0][0] === r[r.length - 1][0] && r[0][1] === r[r.length - 1][1] ? -1 : undefined).map((p) => toWorld(p, scale))
      if (pts.length < 3) return
      const positive = Clipper.isPositiveD(pts)
      source.push((i === 0) === positive ? pts : pts.reverse())
    })
  }
  if (!source.length) return []
  const inset = Clipper.inflatePathsD(source, -BAND_PX, JoinType.Miter, EndType.Polygon, MITER_LIMIT, PRECISION)
  const tree = new PolyTreeD()
  Clipper.booleanOpDWithPolyTree(ClipType.Difference, source, inset, tree, FillRule.NonZero, PRECISION)
  const out: Position[][][] = []
  collect(tree, scale, out)
  return out
}
