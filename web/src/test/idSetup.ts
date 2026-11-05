import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { IdAdapter, ID_SCHEMA_FILES, initIdData, primeIdData } from '@/editor/id/adapter'
import type { ViewState } from '@/editor/types'

const root = resolve(import.meta.dirname, '../../../node_modules') + '/'
const json = (path: string) => JSON.parse(readFileSync(root + path, 'utf8'))

export const fixtureXml = readFileSync(resolve(import.meta.dirname, '../editor/id/fixtures/katerinky.osm'), 'utf8')

export const fixtureView: ViewState = { center: [17.8805, 49.9405], zoom: 17, width: 1200, height: 800, bounds: [[17.876, 49.938], [17.885, 49.943]] }

export async function primeId(): Promise<void> {
  const schema = '@openstreetmap/id-tagging-schema/dist/'
  const files: Record<string, unknown> = {
    languages: json('@openstreetmap/id/dist/data/languages.min.json'),
    locale_general_en: json('@openstreetmap/id/dist/locales/en.min.json'),
  }
  for (const [key, file] of Object.entries(ID_SCHEMA_FILES)) files[key] = json(schema + file.replace(/\.json$/, '.min.json'))
  primeIdData(files)
  await initIdData()
}

export function fixtureAdapter(): IdAdapter {
  const a = new IdAdapter(fixtureView)
  a.loadXml(fixtureXml, [[17.878, 49.939], [17.883, 49.942]])
  return a
}

export function readRepoFile(path: string): string {
  return readFileSync(resolve(import.meta.dirname, '../../..', path), 'utf8')
}
