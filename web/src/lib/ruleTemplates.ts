import type { RuleKind } from '@/generated/models'

export const TEMPLATE_KINDS: { kind: RuleKind; label: string; hint: string }[] = [
  { kind: 'highlight', label: 'Highlight', hint: 'Style objects on the map, with a legend and coverage stats' },
  { kind: 'check', label: 'Check', hint: 'A validator rule with a message and an optional fix' },
  { kind: 'preset', label: 'Preset overlay', hint: 'Extend an iD preset with your own tags and fields' },
  { kind: 'smart_preset', label: 'Smart preset', hint: 'A multi-step edit with parameters' },
]

export function slug(name: string): string {
  return name.trim().toLowerCase().replace(/[^a-z0-9_.-]+/g, '-').replace(/^-+|-+$/g, '')
}

export function pathFor(kind: RuleKind, name: string): string | null {
  const s = slug(name).replace(/\.validator\.mapcss$|\.mapcss$|\.json$/, '')
  if (!s) return null
  switch (kind) {
    case 'highlight':
      return `highlights/${s}.mapcss`
    case 'check':
      return `checks/${s}.validator.mapcss`
    case 'preset':
      return `presets/${s}.json`
    case 'smart_preset':
      return `presets/smart/${s}.json`
    case 'sources':
      return 'sources.json'
  }
}

export function templateFor(kind: RuleKind, name: string): string {
  const title = name.trim() || 'Untitled'
  const id = slug(name) || 'untitled'
  switch (kind) {
    case 'highlight':
      return `meta {
  title: "${title}";
  description: "What this highlight shows.";
  -ore-dim-others: true;
  -ore-stats: length;
}

way[highway=footway][footway=sidewalk] {
  color: #1a9850;
  width: 4;
  -ore-legend: "sidewalk";
}

way[highway=footway][footway=sidewalk][!surface] {
  color: #d73027;
  width: 4;
  dashes: 6, 4;
  -ore-legend: "sidewalk without surface";
}
`
    case 'check':
      return `meta {
  title: "${title}";
}

way[highway=footway][!surface] {
  throwWarning: "Footway without surface";
  group: "${title}";
  assertMatch: "way highway=footway";
  assertNoMatch: "way highway=footway surface=asphalt";
}
`
    case 'preset':
      return `${JSON.stringify(
        {
          id: `custom/${id}`,
          extends: 'highway/footway/sidewalk',
          name: title,
          apply: 'merge',
          addTags: { highway: 'footway', footway: 'sidewalk', surface: 'paving_stones' },
          fields: ['{highway/footway/sidewalk}', 'surface', 'width'],
        },
        null,
        2,
      )}
`
    case 'smart_preset':
      return `${JSON.stringify(
        {
          id: `smart/${id}`,
          name: title,
          geometry: ['line'],
          match: 'way[highway=footway]',
          description: 'Set the surface of a footway.',
          params: { surface: { type: 'combo', options: ['asphalt', 'paving_stones', 'concrete'], default: 'asphalt' } },
          steps: [{ op: 'tag', target: 'self', set: { surface: '{surface}' } }],
        },
        null,
        2,
      )}
`
    case 'sources':
      return '[]\n'
  }
}
