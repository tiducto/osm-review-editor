import { addProtocol, type Map as MlMap } from 'maplibre-gl'
import TileWorker from './tile.worker?worker'
import type { TileRequest, TileResponse } from './tile.worker'
import { ensureIcon } from './icons'

export const PROTOCOL = 'ore-osm'

interface Done {
  data: ArrayBuffer
  icons: string[]
  dashes: string[]
}

const workers: Worker[] = []
const waiting = new Map<number, { resolve: (d: Done) => void; reject: (e: Error) => void }>()
let seq = 0
let next = 0
let map: MlMap | null = null
let onDashes: ((keys: string[]) => void) | null = null
let gate: Promise<unknown> = Promise.resolve()
const cache = new Map<string, Done>()
const CACHE_TILES = 256

function pool(): Worker[] {
  if (workers.length) return workers
  const n = Math.max(2, Math.min(4, Math.floor((navigator.hardwareConcurrency || 4) / 2)))
  for (let i = 0; i < n; i++) {
    const w = new TileWorker()
    w.onmessage = (e: MessageEvent<TileResponse>) => {
      const r = e.data
      const p = waiting.get(r.id)
      if (!p) return
      waiting.delete(r.id)
      if (r.error) p.reject(new Error(r.error))
      else p.resolve({ data: r.data!, icons: r.icons ?? [], dashes: r.dashes ?? [] })
    }
    workers.push(w)
  }
  return workers
}

function request(url: string, z: number, signal: AbortSignal, raw = false): Promise<Done> {
  const w = pool()[next++ % pool().length]
  const id = ++seq
  return new Promise<Done>((resolve, reject) => {
    waiting.set(id, { resolve, reject })
    w.postMessage({ id, url, z, raw } satisfies TileRequest)
    signal.addEventListener('abort', () => {
      if (!waiting.has(id)) return
      waiting.delete(id)
      w.postMessage({ id, url, z, cancel: true } satisfies TileRequest)
      reject(new DOMException('Aborted', 'AbortError'))
    })
  })
}

let registered = false

export function registerTileProtocol(m: MlMap, dashes: (keys: string[]) => void): void {
  map = m
  onDashes = dashes
  if (registered) return
  registered = true
  addProtocol(PROTOCOL, async (params, abort) => {
    const url = new URL(params.url.slice(PROTOCOL.length + 3), location.origin).href
    const z = Number(/\/tiles\/(\d+)\//.exec(url)?.[1] ?? 0)
    let done = cache.get(url)
    if (done) {
      cache.delete(url)
    } else {
      done = await request(url, z, abort.signal, new URL(url).searchParams.has('raw'))
    }
    cache.set(url, done)
    while (cache.size > CACHE_TILES) cache.delete(cache.keys().next().value!)
    if (done.dashes.length) onDashes?.(done.dashes)
    await gate
    const m = map
    if (m && done.icons.length) await Promise.all(done.icons.map((n) => ensureIcon(m, n)))
    return { data: done.data.slice(0) }
  })
}

export function gateTiles(p: Promise<unknown>): void {
  gate = p.catch(() => undefined)
}

export function clearTileCache(): void {
  cache.clear()
}

export function tileUrl(version: string): string {
  return `${PROTOCOL}://api/tiles/{z}/{x}/{y}.mvt?v=${encodeURIComponent(version)}`
}

export function rawTileUrl(version: string, keys: string[]): string {
  const k = keys.length ? `&keys=${encodeURIComponent(keys.join(','))}` : ''
  return `${PROTOCOL}://api/tiles/{z}/{x}/{y}.mvt?v=${encodeURIComponent(version)}&raw=1${k}`
}
