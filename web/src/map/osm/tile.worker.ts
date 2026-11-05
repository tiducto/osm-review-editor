import { PbfReader } from 'pbf'
import { VectorTile } from '@mapbox/vector-tile'
import { fromVectorTileJs } from '@maplibre/vt-pbf'
import { loadPresets, type PresetMatcher } from './presets'
import { rawLayers, transform } from './transform'

export interface TileRequest {
  id: number
  url: string
  z: number
  raw?: boolean
  cancel?: boolean
}

export interface TileResponse {
  id: number
  data?: ArrayBuffer
  icons?: string[]
  dashes?: string[]
  error?: string
}

let presets: Promise<PresetMatcher> | null = null
const pending = new Map<number, AbortController>()

self.onmessage = async (e: MessageEvent<TileRequest>) => {
  const { id, url, z, raw, cancel } = e.data
  if (cancel) {
    pending.get(id)?.abort()
    pending.delete(id)
    return
  }
  const ctrl = new AbortController()
  pending.set(id, ctrl)
  try {
    if (!raw) presets ??= loadPresets()
    const [res, matcher] = await Promise.all([fetch(url, { signal: ctrl.signal }), raw ? null : presets])
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`)
    const buf = await res.arrayBuffer()
    if (buf.byteLength === 0) {
      self.postMessage({ id, data: buf, icons: [], dashes: [] } satisfies TileResponse, { transfer: [buf] })
      return
    }
    const tile = new VectorTile(new PbfReader(buf))
    const t = raw || !matcher ? { layers: rawLayers(tile), icons: [], dashes: [] } : transform(tile, matcher, z)
    const bytes = fromVectorTileJs({ layers: t.layers })
    const data = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
    self.postMessage({ id, data, icons: t.icons, dashes: t.dashes } satisfies TileResponse, { transfer: [data] })
  } catch (err) {
    if (!ctrl.signal.aborted) self.postMessage({ id, error: String(err) } satisfies TileResponse)
  } finally {
    pending.delete(id)
  }
}
