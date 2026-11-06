import type { Page } from '@playwright/test'
import { panels } from './panels'
import { BLANK_PNG, expect, jumpTo, openMap, settle, test, TILE_HOSTS } from './helpers'

type MapWindow = Window & { __oreMap: any }

async function recordTiles(page: Page): Promise<string[]> {
  const seen: string[] = []
  await page.route(TILE_HOSTS, (r) => {
    seen.push(r.request().url())
    return r.fulfill({ status: 200, contentType: 'image/png', body: BLANK_PNG })
  })
  return seen
}

const rasterLayers = (page: Page) =>
  page.evaluate(() => {
    const m = (window as unknown as MapWindow).__oreMap
    return m
      .getStyle()
      .layers.filter((l: any) => l.type === 'raster')
      .map((l: any) => ({ id: l.id, tiles: m.getSource(l.source).tiles?.[0] as string, maxzoom: m.getSource(l.source).maxzoom, tileSize: m.getSource(l.source).tileSize }))
  })

test('the ČÚZK orthophoto is the default background', async ({ page }) => {
  const seen = await recordTiles(page)
  await openMap(page, { zoom: 17, background: null, stubTiles: false })
  await panels.tab(page, 'map').click()
  await expect(panels.background(page, 'cuzk-ortofoto')).toBeChecked()
  const layers = await rasterLayers(page)
  expect(layers).toHaveLength(1)
  expect(layers[0].tiles).toBe('https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}')
  expect(seen.some((u) => u.includes('/ORTOFOTO_WM/MapServer/tile/'))).toBe(true)
  const hidden = await page.evaluate(() => {
    const v: unknown = (window as unknown as MapWindow).__oreMap.getLayoutProperty('osm-bg', 'visibility')
    return String(v)
  })
  expect(hidden).toBe('none')
})

test('OpenStreetMap Standard loads its tiles, overzooms past 19 and persists', async ({ page }) => {
  const seen = await recordTiles(page)
  await openMap(page, { zoom: 17, background: null, stubTiles: false })
  await panels.tab(page, 'map').click()
  await panels.background(page, 'osm-standard').check()
  await settle(page)
  const [layer] = await rasterLayers(page)
  expect(layer).toMatchObject({ tiles: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', maxzoom: 19, tileSize: 256 })
  const osm = () => seen.filter((u) => u.startsWith('https://tile.openstreetmap.org/'))
  await expect.poll(() => osm().length).toBeGreaterThan(0)
  for (const u of osm()) expect(u).toMatch(/^https:\/\/tile\.openstreetmap\.org\/\d+\/\d+\/\d+\.png$/)
  await jumpTo(page, 20.5)
  const zooms = osm().map((u) => Number(u.split('/')[3]))
  expect(Math.max(...zooms)).toBeLessThanOrEqual(19)
  await page.reload()
  await page.waitForFunction(() => (window as unknown as MapWindow).__oreMap?.isStyleLoaded())
  await panels.tab(page, 'map').click()
  await expect(panels.background(page, 'osm-standard')).toBeChecked()
})

test('overlays are separate toggles drawn over the background', async ({ page }) => {
  await recordTiles(page)
  await openMap(page, { zoom: 17, background: null, stubTiles: false })
  await panels.tab(page, 'map').click()
  await panels.overlay(page, 'cuzk-km').check()
  await expect.poll(async () => (await rasterLayers(page)).map((l) => l.id)).toEqual(['ore-imagery', 'ore-overlay-cuzk-km'])
  await panels.background(page, 'esri-world-imagery').check()
  await expect.poll(async () => (await rasterLayers(page))[0]?.tiles ?? '').toContain('arcgisonline.com')
  await expect.poll(async () => (await rasterLayers(page)).map((l) => l.id)).toEqual(['ore-imagery', 'ore-overlay-cuzk-km'])
  await panels.overlay(page, 'cuzk-km').uncheck()
  await expect.poll(async () => (await rasterLayers(page)).map((l) => l.id)).toEqual(['ore-imagery'])
})
