import { mapCssParse, mapCssStyles } from 'osm-review-shared'

const sheets = new Map<string, unknown>()

function sheetFor(selector: string): unknown {
  if (sheets.has(selector)) return sheets.get(selector)
  const parsed = mapCssParse(`${selector} { color: red; }`, 'smart-preset')
  const sheet = parsed.ok && parsed.ruleCount > 0 ? parsed.stylesheet : null
  sheets.set(selector, sheet)
  return sheet
}

export function selectorValid(selector: string): boolean {
  return sheetFor(selector) != null
}

function numericId(id: string): number {
  const n = Number(id.slice(1))
  return Number.isFinite(n) ? n : 0
}

export function mapCssFeature(graph: any, entity: any): any {
  const f: any = {
    type: entity.type,
    id: numericId(entity.id),
    tags: entity.tags ?? {},
    closed: entity.type === 'way' && entity.isClosed(),
  }
  f.parents = () => {
    const out: any[] = []
    if (entity.type === 'node') {
      for (const w of graph.parentWays(entity)) out.push({ element: mapCssFeature(graph, w), role: '', index: w.nodes.indexOf(entity.id) })
    }
    for (const r of graph.parentRelations(entity)) {
      const index = r.members.findIndex((m: any) => m.id === entity.id)
      out.push({ element: mapCssFeature(graph, r), role: r.members[index]?.role ?? '', index })
    }
    return out
  }
  f.children = () => {
    if (entity.type === 'way') {
      return entity.nodes.map((n: string, index: number) => ({ element: mapCssFeature(graph, graph.entity(n)), role: '', index }))
    }
    if (entity.type === 'relation') {
      const out: any[] = []
      entity.members.forEach((m: any, index: number) => {
        const child = graph.hasEntity(m.id)
        if (child) out.push({ element: mapCssFeature(graph, child), role: m.role ?? '', index })
      })
      return out
    }
    return []
  }
  return f
}

export function selectorMatches(selector: string, graph: any, entity: any): boolean {
  const sheet = sheetFor(selector)
  if (sheet == null) throw new Error(`Invalid selector: ${selector}`)
  const styled = mapCssStyles(sheet, mapCssFeature(graph, entity), null) as { rules: number[] }
  return styled.rules.includes(0)
}
