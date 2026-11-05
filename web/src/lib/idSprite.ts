import sprite from '@openstreetmap/id/dist/img/iD-sprite.svg?raw'

export interface SpriteSymbol {
  viewBox: string
  body: string
}

let table: Map<string, SpriteSymbol> | null = null

function parse(): Map<string, SpriteSymbol> {
  const out = new Map<string, SpriteSymbol>()
  const re = /<symbol\b([^>]*)>([\s\S]*?)<\/symbol>/g
  for (const m of sprite.matchAll(re)) {
    const id = /\bid="([^"]+)"/.exec(m[1])?.[1]
    if (!id) continue
    const viewBox = /\bviewBox="([^"]+)"/.exec(m[1])?.[1] ?? '0 0 20 20'
    out.set(id, { viewBox, body: m[2] })
  }
  return out
}

export function spriteSymbol(id: string): SpriteSymbol | null {
  table ??= parse()
  return table.get(id) ?? null
}
