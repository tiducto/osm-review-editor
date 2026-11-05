import { Map as MlMap, setWorkerUrl, type GeoJSONSource } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import 'maplibre-gl/dist/maplibre-gl.css'
import { autoHidden, build, type ApiFeature } from './build'
import { addAreaIcon, addOneway, addPin, addShield, addVertexIcon } from './icons'
import { loadPresets } from './presets'
import { buildStyle, dashExpr, LINE_LAYER_IDS, lowZoomThreshold } from './style'

setWorkerUrl(workerUrl)

const q = new URLSearchParams(location.search)
const lat = Number(q.get('lat') ?? 49.9405)
const lon = Number(q.get('lon') ?? 17.8935)
const idZoom = Number(q.get('z') ?? 17)
const imagery = q.get('imagery') ?? undefined
const autoHide = q.get('autohide') !== '0'

const presetsPromise = loadPresets()
const status = document.getElementById('status')!

const map = new MlMap({
  container: 'map',
  style: buildStyle(lat, new Map([['solid', [1, 0]]]), imagery),
  center: [lon, lat],
  zoom: idZoom - 1,
  attributionControl: false,
  fadeDuration: 0,
})

let seq = 0
async function refresh() {
  if (map.getZoom() < 14.5) return
  const my = ++seq
  const b = map.getBounds()
  const bbox = [b.getWest(), b.getSouth(), b.getEast(), b.getNorth()].map((x) => x.toFixed(6)).join(',')
  status.textContent = 'loading'
  const [presets, res] = await Promise.all([presetsPromise, fetch(`/api/features?bbox=${bbox}&limit=20000`)])
  const json = (await res.json()) as { features: ApiFeature[] }
  if (my !== seq) return
  const canvas = map.getCanvas()
  const hidden = autoHide ? autoHidden(json.features, [canvas.clientWidth, canvas.clientHeight]) : undefined
  const built = build(json.features, presets, hidden)
  addShield(map)
  addOneway(map)
  await Promise.all([
    ...[...built.pins].map((p) => {
      const [icon, wd] = p.split('|')
      return addPin(map, icon || null, wd === 'w')
    }),
    ...[...built.vertexIcons].map((i) => addVertexIcon(map, i)),
    ...[...built.areaIcons].map((i) => addAreaIcon(map, i)),
  ])
  if (my !== seq) return
  for (const id of LINE_LAYER_IDS) {
    const p = id.endsWith('casing') ? 'c' : 's'
    map.setPaintProperty(id, 'line-dasharray', [
      'step', ['zoom'], dashExpr(built.dashes, p + 'dl'), lowZoomThreshold(lat), dashExpr(built.dashes, p + 'd'),
    ])
  }
  const set = (id: string, data: GeoJSON.FeatureCollection) => (map.getSource(id) as GeoJSONSource).setData(data)
  set('areas', built.areas)
  set('lines', built.lines)
  set('oneways', built.oneways)
  set('vertices', built.vertices)
  set('points', built.points)
  set('arealabels', built.areaLabels)
  map.once('idle', () => {
    status.textContent = `ready ${json.features.length}` + (hidden ? ' hidden:' + Object.entries(hidden).filter(([, v]) => v).map(([k]) => k).join(',') : '')
    document.body.dataset.ready = '1'
  })
}

map.on('load', refresh)
map.on('moveend', refresh)
Object.assign(window, { map })
