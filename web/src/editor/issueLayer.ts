import type { GeoJSONSource, Map as MlMap } from 'maplibre-gl'
import type { Feature, FeatureCollection } from 'geojson'
import type { EditIssue } from './validation/issues'
import { FONT } from '@/map/osm/style'

export const ISSUE_SOURCE = 'ore-edit-issues'
export const ISSUE_LAYER = 'edit-issue-marker'
const ISSUE_RING = 'edit-issue-ring'

export const ISSUE_COLORS: Record<string, string> = { error: '#e03131', warning: '#f08c00', info: '#1c7ed6', suggestion: '#1c7ed6' }

function collection(issues: EditIssue[], focused: string | null): FeatureCollection {
  const features: Feature[] = []
  issues.forEach((i, n) => {
    if (!i.loc) return
    features.push({
      type: 'Feature',
      id: n + 1,
      geometry: { type: 'Point', coordinates: i.loc },
      properties: { issue: i.id, severity: i.severity, focused: i.id === focused, rank: i.severity === 'error' ? 2 : i.severity === 'warning' ? 1 : 0 },
    })
  })
  return { type: 'FeatureCollection', features }
}

export function setIssueMarkers(map: MlMap, issues: EditIssue[], focused: string | null) {
  const data = collection(issues, focused)
  const src = map.getSource(ISSUE_SOURCE) as GeoJSONSource | undefined
  if (src) {
    src.setData(data)
    return
  }
  map.addSource(ISSUE_SOURCE, { type: 'geojson', data })
  const color = ['match', ['get', 'severity'], 'error', ISSUE_COLORS.error, 'warning', ISSUE_COLORS.warning, ISSUE_COLORS.info] as unknown as string
  map.addLayer({
    id: ISSUE_RING,
    type: 'circle',
    source: ISSUE_SOURCE,
    filter: ['==', ['get', 'focused'], true],
    paint: { 'circle-radius': 13, 'circle-color': 'rgba(0,0,0,0)', 'circle-stroke-color': color, 'circle-stroke-width': 2.5 },
  })
  map.addLayer({
    id: ISSUE_LAYER,
    type: 'symbol',
    source: ISSUE_SOURCE,
    layout: {
      'symbol-sort-key': ['get', 'rank'],
      'text-field': '!',
      'text-font': FONT,
      'text-size': 11,
      'text-allow-overlap': true,
      'icon-allow-overlap': true,
    },
    paint: { 'text-color': '#fff' },
  })
  map.addLayer(
    {
      id: `${ISSUE_LAYER}-dot`,
      type: 'circle',
      source: ISSUE_SOURCE,
      paint: { 'circle-radius': 7, 'circle-color': color, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5 },
    },
    ISSUE_LAYER,
  )
}

export function issueLayerIds(): string[] {
  return [ISSUE_LAYER, `${ISSUE_LAYER}-dot`]
}

export function removeIssueMarkers(map: MlMap) {
  for (const id of [ISSUE_LAYER, `${ISSUE_LAYER}-dot`, ISSUE_RING]) if (map.getLayer(id)) map.removeLayer(id)
  if (map.getSource(ISSUE_SOURCE)) map.removeSource(ISSUE_SOURCE)
}
