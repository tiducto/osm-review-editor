import type { Map as MlMap } from 'maplibre-gl'
import { api, errorMessage, isAbort } from '@/api/client'
import { createIdAdapter, ID_EDITOR_VERSION, loadIdData, OPERATION_KEYS, type IdAdapter } from './id/adapter'
import { EditController, type ControllerHooks } from './controller'
import type { Bounds } from './types'

export * as presetOps from './id/presetOps'

export const LOAD_ZOOM = 15
const TILE_ZOOM = 15
const CONCURRENCY = 3

export interface LoaderHooks {
  busy(n: number): void
  loaded(): void
  error(text: string): void
}

function tileBounds(x: number, y: number, z: number): Bounds {
  const n = 2 ** z
  const lon = (i: number) => (i / n) * 360 - 180
  const lat = (j: number) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * j) / n))) * 180) / Math.PI
  return [
    [lon(x), lat(y + 1)],
    [lon(x + 1), lat(y)],
  ]
}

function tilesFor(b: Bounds, z = TILE_ZOOM): [number, number][] {
  const n = 2 ** z
  const tx = (lon: number) => Math.floor(((lon + 180) / 360) * n)
  const ty = (lat: number) => {
    const r = (lat * Math.PI) / 180
    return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * n)
  }
  const out: [number, number][] = []
  for (let x = tx(b[0][0]); x <= tx(b[1][0]); x++) for (let y = ty(b[1][1]); y <= ty(b[0][1]); y++) out.push([x, y])
  return out
}

export class TileLoader {
  private done = new Set<string>()
  private failed = new Map<string, number>()
  private queue: [number, number][] = []
  private running = new Map<string, AbortController>()
  private waiters: (() => void)[] = []

  constructor(
    private adapter: IdAdapter,
    private hooks: LoaderHooks,
  ) {}

  request(bounds: Bounds, limit = 60): number {
    const wanted = tilesFor(bounds).filter(([x, y]) => {
      const key = `${x}/${y}`
      if (this.done.has(key) || this.running.has(key)) return false
      const f = this.failed.get(key)
      return !f || Date.now() - f > 30000
    })
    const take = wanted.slice(0, limit)
    const queued = new Set(this.queue.map(([x, y]) => `${x}/${y}`))
    for (const t of take) if (!queued.has(`${t[0]}/${t[1]}`)) this.queue.push(t)
    this.pump()
    return wanted.length
  }

  idle(): Promise<void> {
    if (!this.queue.length && !this.running.size) return Promise.resolve()
    return new Promise((resolve) => this.waiters.push(resolve))
  }

  cancel() {
    this.queue = []
    for (const c of this.running.values()) c.abort()
    this.running.clear()
    this.settle()
  }

  private settle() {
    this.hooks.busy(this.running.size + this.queue.length)
    if (!this.queue.length && !this.running.size) {
      const w = this.waiters
      this.waiters = []
      w.forEach((f) => f())
    }
  }

  private pump() {
    while (this.running.size < CONCURRENCY && this.queue.length) {
      const [x, y] = this.queue.shift()!
      const key = `${x}/${y}`
      const ctrl = new AbortController()
      this.running.set(key, ctrl)
      const b = tileBounds(x, y, TILE_ZOOM)
      void api
        .osmMap({ west: b[0][0], south: b[0][1], east: b[1][0], north: b[1][1] }, ctrl.signal)
        .then((xml) => {
          this.adapter.loadXml(xml, b)
          this.done.add(key)
          this.hooks.loaded()
        })
        .catch((e) => {
          if (isAbort(e)) return
          this.failed.set(key, Date.now())
          this.hooks.error(`Loading OSM data failed: ${errorMessage(e)}`)
        })
        .finally(() => {
          this.running.delete(key)
          this.pump()
          this.settle()
        })
    }
    this.settle()
  }
}

export interface EditSession {
  adapter: IdAdapter
  controller: EditController
  loader: TileLoader
  version: string
  operationKeys: Record<string, string>
  dispose(): void
}

export async function createEditSession(map: MlMap, hooks: ControllerHooks & LoaderHooks): Promise<EditSession> {
  await loadIdData()
  const adapter = createIdAdapter()
  const controller = new EditController(map, adapter, hooks)
  const loader = new TileLoader(adapter, {
    busy: hooks.busy,
    error: hooks.error,
    loaded: () => {
      controller.refreshHidden()
      controller.scheduleRender()
      hooks.loaded()
    },
  })
  const onMoveEnd = () => {
    if (map.getZoom() < LOAD_ZOOM) return
    const b = map.getBounds()
    loader.request([
      [b.getWest(), b.getSouth()],
      [b.getEast(), b.getNorth()],
    ])
  }
  controller.attach()
  map.on('moveend', onMoveEnd)
  onMoveEnd()
  return {
    adapter,
    controller,
    loader,
    version: ID_EDITOR_VERSION,
    operationKeys: OPERATION_KEYS,
    dispose() {
      map.off('moveend', onMoveEnd)
      loader.cancel()
      controller.detach()
    },
  }
}
