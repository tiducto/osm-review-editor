import type { RuleKind } from '@/generated/models'
import { t } from '@/i18n'

type TemplateKind = 'highlight' | 'check' | 'preset' | 'smart_preset'

function templateKind(kind: TemplateKind): { kind: RuleKind; readonly label: string; readonly hint: string; readonly newTitle: string } {
  return {
    kind,
    get label() {
      return t(`template.${kind}.label`)
    },
    get hint() {
      return t(`template.${kind}.hint`)
    },
    get newTitle() {
      return t(`template.${kind}.new`)
    },
  }
}

export const TEMPLATE_KINDS = (['highlight', 'check', 'preset', 'smart_preset'] as const).map(templateKind)

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
