import type { Map as MlMap } from 'maplibre-gl'
import temakiUrl from '@openstreetmap/id/dist/img/temaki-sprite.svg?url'
import makiUrl from '@openstreetmap/id/dist/img/maki-sprite.svg?url'
import faUrl from '@openstreetmap/id/dist/img/fa-sprite.svg?url'
import idUrl from '@openstreetmap/id/dist/img/iD-sprite.svg?url'
import roentgenUrl from '@openstreetmap/id/dist/img/roentgen-sprite.svg?url'
import pinheadUrl from '@openstreetmap/id/dist/img/pinhead-sprite.svg?url'

const PR = 2
const SPRITES: [RegExp, string][] = [
  [/^temaki-/, temakiUrl],
  [/^maki-/, makiUrl],
  [/^fa[srb]-/, faUrl],
  [/^iD-/, idUrl],
  [/^roentgen-/, roentgenUrl],
  [/^pinhead-/, pinheadUrl],
]

const PIN_PATH = 'M 17,8 C 17,13 11,21 8.5,23.5 C 6,21 0,13 0,8 C 0,4 4,-0.5 8.5,-0.5 C 13,-0.5 17,4 17,8 z'

interface Symbol {
  viewBox: string
  body: string
}

const docs = new Map<string, Promise<Document>>()
const symbols = new Map<string, Symbol | null>()

async function spriteDoc(url: string): Promise<Document> {
  let p = docs.get(url)
  if (!p) {
    p = fetch(url)
      .then((r) => r.text())
      .then((t) => new DOMParser().parseFromString(t, 'image/svg+xml'))
    docs.set(url, p)
  }
  return p
}

async function symbolFor(icon: string): Promise<Symbol | null> {
  if (symbols.has(icon)) return symbols.get(icon)!
  const entry = SPRITES.find(([re]) => re.test(icon))
  let result: Symbol | null = null
  if (entry) {
    const doc = await spriteDoc(entry[1])
    const el = doc.getElementById(icon)
    if (el) {
      const ser = new XMLSerializer()
      result = {
        viewBox: el.getAttribute('viewBox') ?? '0 0 15 15',
        body: Array.from(el.childNodes).map((n) => ser.serializeToString(n)).join(''),
      }
    }
  }
  symbols.set(icon, result)
  return result
}

export async function iconMarkup(icon: string): Promise<string | null> {
  const sym = await symbolFor(icon)
  if (!sym) return null
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${sym.viewBox}" width="100%" height="100%" fill="currentColor">${sym.body}</svg>`
}

function loadImage(svg: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
  })
}

async function iconImage(sym: Symbol, size: number, color: string, halo?: { color: string; width: number; opacity: number }): Promise<HTMLImageElement> {
  const vb = sym.viewBox.split(/[\s,]+/).map(Number)
  const unit = vb[2] / size
  const haloGroup = halo
    ? `<g fill="${halo.color}" stroke="${halo.color}" stroke-width="${halo.width * unit}" stroke-linejoin="round" opacity="${halo.opacity}" color="${halo.color}">${sym.body}</g>`
    : ''
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${size * PR}" height="${size * PR}" viewBox="${sym.viewBox}" overflow="visible">${haloGroup}<g fill="${color}" color="${color}">${sym.body}</g></svg>`
  return loadImage(svg)
}

function canvas(w: number, h: number): [HTMLCanvasElement, CanvasRenderingContext2D] {
  const c = document.createElement('canvas')
  c.width = Math.ceil(w * PR)
  c.height = Math.ceil(h * PR)
  const ctx = c.getContext('2d')!
  ctx.scale(PR, PR)
  return [c, ctx]
}

function add(map: MlMap, name: string, c: HTMLCanvasElement, extra: object = {}) {
  if (map.hasImage(name)) return
  const data = c.getContext('2d')!.getImageData(0, 0, c.width, c.height)
  map.addImage(name, { width: c.width, height: c.height, data: new Uint8Array(data.data.buffer) }, { pixelRatio: PR, ...extra })
}

export const PIN_W = 19
export const PIN_H = 27
export const PIN_ANCHOR: [number, number] = [9.5, 25]

export async function addPin(map: MlMap, icon: string | null, wikidata: boolean): Promise<string> {
  const name = `pin:${icon ?? ''}:${wikidata ? 'w' : ''}`
  if (map.hasImage(name)) return name
  const [c, ctx] = canvas(PIN_W, PIN_H)
  ctx.save()
  ctx.translate(PIN_ANCHOR[0] - 8, PIN_ANCHOR[1] - 23)
  const p = new Path2D(PIN_PATH)
  ctx.fillStyle = wikidata ? '#eee' : '#fff'
  ctx.fill(p)
  ctx.lineWidth = wikidata ? 2 : 1
  ctx.strokeStyle = wikidata ? '#666' : '#444'
  ctx.stroke(p)
  ctx.restore()
  const sym = icon ? await symbolFor(icon) : null
  if (sym) {
    const img = await iconImage(sym, 12, wikidata ? '#666' : '#333')
    ctx.drawImage(img, PIN_ANCHOR[0] - 5.5, PIN_ANCHOR[1] - 20, 12, 12)
  }
  add(map, name, c)
  return name
}

function blank(map: MlMap, name: string): null {
  if (!map.hasImage(name)) add(map, name, canvas(1, 1)[0])
  return null
}

export async function addVertexIcon(map: MlMap, icon: string): Promise<string | null> {
  const name = `vicon:${icon}`
  if (map.hasImage(name)) return name
  const sym = await symbolFor(icon)
  if (!sym) return blank(map, name)
  const [c, ctx] = canvas(12, 12)
  ctx.drawImage(await iconImage(sym, 12, '#333'), 0, 0, 12, 12)
  add(map, name, c)
  return name
}

export async function addAreaIcon(map: MlMap, icon: string): Promise<string | null> {
  const name = `aicon:${icon}`
  if (map.hasImage(name)) return name
  const sym = await symbolFor(icon)
  if (!sym) return blank(map, name)
  const [c, ctx] = canvas(21, 21)
  const img = await iconImage(sym, 17, 'rgba(34,34,34,0.8)', { color: '#999', width: 2, opacity: 0.6 })
  ctx.drawImage(img, 2, 2, 17, 17)
  add(map, name, c)
  return name
}

export function addShield(map: MlMap) {
  const w = 16
  const h = 17
  const [c, ctx] = canvas(w, h)
  const r = 3.5
  const p = new Path2D()
  p.moveTo(0.5 + r, 0.5)
  p.lineTo(w - 0.5 - r, 0.5)
  p.arc(w - 0.5, 0.5, r, Math.PI, Math.PI / 2, true)
  p.lineTo(w - 0.5, h - 0.5 - r)
  p.arc(w - 0.5, h - 0.5, r, -Math.PI / 2, Math.PI, true)
  p.lineTo(0.5 + r, h - 0.5)
  p.arc(0.5, h - 0.5, r, 0, -Math.PI / 2, true)
  p.lineTo(0.5, 0.5 + r)
  p.arc(0.5, 0.5, r, Math.PI / 2, 0, true)
  p.closePath()
  ctx.fillStyle = '#fff'
  ctx.fill(p)
  ctx.strokeStyle = '#444'
  ctx.lineWidth = 1
  ctx.stroke(p)
  add(map, 'addr-shield', c, {
    stretchX: [[6 * PR, 10 * PR]],
    stretchY: [[6 * PR, 11 * PR]],
    content: [4 * PR, 1 * PR, 12 * PR, 16 * PR],
  })
}

export function addOneway(map: MlMap) {
  for (const [name, color] of [['oneway-black', '#333'], ['oneway-white', '#fff']]) {
    const [c, ctx] = canvas(10, 5)
    ctx.fillStyle = color
    ctx.fill(new Path2D('M 6,3 L 0,3 L 0,2 L 6,2 L 5,0 L 10,2.5 L 5,5 z'))
    add(map, name, c)
  }
}

const pendingIcons = new Map<string, Promise<unknown>>()

export function ensureIcon(map: MlMap, name: string): Promise<unknown> {
  if (map.hasImage(name)) return Promise.resolve()
  let p = pendingIcons.get(name)
  if (!p) {
    const [kind, icon, flag] = name.split(':')
    p =
      kind === 'pin' ? addPin(map, icon || null, flag === 'w')
      : kind === 'vicon' ? addVertexIcon(map, icon)
      : kind === 'aicon' ? addAreaIcon(map, icon)
      : Promise.resolve()
    p = p.catch(() => undefined).finally(() => pendingIcons.delete(name))
    pendingIcons.set(name, p)
  }
  return p
}

const PATTERN_PNGS = import.meta.glob('../../../../node_modules/@openstreetmap/id/dist/img/pattern/*.png', {
  query: '?url',
  import: 'default',
  eager: true,
}) as Record<string, string>

function loadUrl(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = url
  })
}

export async function addPatterns(map: MlMap, patterns: Record<string, [string, string]>): Promise<void> {
  const byFile = new Map<string, string>()
  for (const [path, url] of Object.entries(PATTERN_PNGS)) byFile.set(path.replace(/^.*\/(\w+)\.png$/, '$1'), url)
  await Promise.all(
    Object.entries(patterns).map(async ([name, [file, color]]) => {
      const id = `pat:${name}`
      if (map.hasImage(id)) return
      const url = byFile.get(file)
      const img = url ? await loadUrl(url).catch(() => null) : null
      const [c, ctx] = canvas(32, 32)
      ctx.fillStyle = color
      ctx.fillRect(0, 0, 32, 32)
      if (img) ctx.drawImage(img, 0, 0, 32, 32)
      if (!map.hasImage(id)) add(map, id, c)
    }),
  )
}
