import { t } from '@/i18n'

type Cond =
  | { op: 'eq'; key: string; values: string[] }
  | { op: 'ne'; key: string; values: string[] }
  | { op: 'has'; key: string }
  | { op: 'not'; key: string }
  | { op: 'like'; key: string; text: string }

export interface TagFilter {
  conds: Cond[]
  error: string | null
}

export function parseTagFilter(input: string): TagFilter {
  const conds: Cond[] = []
  for (const raw of input.trim().split(/\s+/).filter(Boolean)) {
    let m: RegExpExecArray | null
    if ((m = /^!([^=!~]+)$/.exec(raw))) conds.push({ op: 'not', key: m[1] })
    else if ((m = /^([^=!~]+)!=(.+)$/.exec(raw))) conds.push({ op: 'ne', key: m[1], values: m[2].split('|') })
    else if ((m = /^([^=!~]+)=\*$/.exec(raw))) conds.push({ op: 'has', key: m[1] })
    else if ((m = /^([^=!~]+)=(.+)$/.exec(raw))) conds.push({ op: 'eq', key: m[1], values: m[2].split('|') })
    else if ((m = /^([^=!~]+)~(.+)$/.exec(raw))) conds.push({ op: 'like', key: m[1], text: m[2].toLowerCase() })
    else if ((m = /^([^=!~]+)$/.exec(raw))) conds.push({ op: 'has', key: m[1] })
    else return { conds: [], error: t('app.tagFilterError', { raw }) }
  }
  return { conds, error: null }
}

export function matchesTags(f: TagFilter, tags: Record<string, string>): boolean {
  return f.conds.every((c) => {
    const v = tags[c.key]
    switch (c.op) {
      case 'eq':
        return v !== undefined && c.values.includes(v)
      case 'ne':
        return v === undefined || !c.values.includes(v)
      case 'has':
        return v !== undefined
      case 'not':
        return v === undefined
      case 'like':
        return v !== undefined && v.toLowerCase().includes(c.text)
    }
  })
}
