import { panels } from './panels'
import type { Page } from '@playwright/test'
import { captureTransition, CENTRE, expect, expectSteady, jumpTo, openMap, renderedCount, settle, test, visibleMapRect } from './helpers'

async function featurePoint(page: Page): Promise<[number, number]> {
  const rect = await visibleMapRect(page)
  return page.evaluate((rect) => {
    const m = (window as unknown as { __oreMap: any }).__oreMap
    const r = m.getContainer().getBoundingClientRect()
    const others = ['osm-pin', 'osm-addr', 'osm-vertex', 'osm-line-hit', 'osm-area-hit', 'osm-point-dot', 'osm-pin-label', 'osm-area-label', 'osm-area-icon']
    for (let y = rect.y + 80; y < rect.y + rect.h - 40; y += 8) {
      for (let x = rect.x + 40; x < rect.x + rect.w - 40; x += 8) {
        const px = x - r.left
        const py = y - r.top
        const top = m.queryRenderedFeatures([px, py], { layers: ['osm-fill'] })[0]
        if (!top || top.properties['@bld'] !== true) continue
        const near = m.queryRenderedFeatures(
          [
            [px - 8, py - 8],
            [px + 8, py + 8],
          ],
          { layers: others.filter((l) => m.getLayer(l)) },
        )
        if (near.length === 0) return [x, y] as [number, number]
      }
    }
    throw new Error('no clickable building in the visible map')
  }, rect)
}

test('buildings render at every zoom step from 13 to 19', async ({ page }) => {
  test.setTimeout(240_000)
  await openMap(page)
  const counts: string[] = []
  for (let z = 13; z <= 19.001; z += 0.25) {
    await jumpTo(page, z)
    const n = await renderedCount(page, { building: true })
    counts.push(`${z.toFixed(2)}:${n}`)
    expect(n, `buildings at zoom ${z.toFixed(2)} (${counts.join(' ')})`).toBeGreaterThan(0)
  }
})

test('building count does not depend on the view size', async ({ page }) => {
  await openMap(page, { zoom: 16 })
  const wide = await renderedCount(page, { building: true })
  await panels.rightToggle(page).click()
  await panels.bottomCollapse(page).click()
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBe(wide)
  await page.setViewportSize({ width: 900, height: 700 })
  await settle(page)
  const smaller = await renderedCount(page, { building: true })
  expect(smaller).toBeGreaterThan(0)
  await page.setViewportSize({ width: 1440, height: 900 })
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBe(wide)
})

test('panels open and close over a still map without blank frames', async ({ page }) => {
  await openMap(page)
  const buildings = await renderedCount(page, { building: true })
  expect(buildings).toBeGreaterThan(0)
  const steps: [string, () => Promise<unknown>][] = [
    ['bottom collapse', () => panels.bottomCollapse(page).click()],
    ['bottom expand', () => panels.bottomExpand(page).click()],
    ['config panel hide', () => panels.rightToggle(page).click()],
    ['config panel show', () => panels.rightToggle(page).click()],
  ]
  for (const [label, action] of steps) {
    expectSteady(await captureTransition(page, action), label)
    expect(await renderedCount(page, { building: true }), `${label}: buildings`).toBe(buildings)
  }
  const [x, y] = await featurePoint(page)
  expectSteady(await captureTransition(page, () => page.mouse.click(x, y)), 'feature panel open')
  await expect(panels.leftPanel(page)).toHaveCSS('width', '392px')
  for (const label of ['feature panel collapse', 'feature panel expand']) {
    expectSteady(await captureTransition(page, () => panels.leftToggle(page).click()), label)
  }
  expectSteady(await captureTransition(page, () => page.keyboard.press('Escape')), 'feature panel close')
  expect(await renderedCount(page, { building: true })).toBe(buildings)
})

test('phone sheet and config drawer open over a still map', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await openMap(page, { zoom: 18.5 })
  expectSteady(await captureTransition(page, () => panels.bottomCollapse(page).click()), 'phone bottom collapse')
  const [x, y] = await featurePoint(page)
  expectSteady(await captureTransition(page, () => page.mouse.click(x, y)), 'phone sheet open')
  expectSteady(await captureTransition(page, () => panels.sheetToggle(page).click()), 'phone sheet collapse')
  expectSteady(await captureTransition(page, () => panels.sheetToggle(page).click()), 'phone sheet expand')
  const buildings = await renderedCount(page, { building: true })
  await panels.rightToggle(page).click()
  await expect(panels.rightPanel(page)).toHaveCSS('width', '296px')
  await panels.rightToggle(page).click()
  await expect(panels.rightPanel(page)).toHaveCSS('width', '0px')
  expectSteady(await captureTransition(page, () => page.waitForTimeout(50)), 'phone after config drawer')
  expect(await renderedCount(page, { building: true })).toBe(buildings)
})

test('map features switches hide and show groups and persist', async ({ page }) => {
  await openMap(page, { zoom: 16.5 })
  await panels.tab(page, 'map').click()
  await page.getByTestId('map-features-toggle').click()
  const buildings = await renderedCount(page, { building: true })
  const roads = await renderedCount(page, { feat: 'traffic_roads' })
  const points = await renderedCount(page, { feat: 'points' })
  expect(buildings).toBeGreaterThan(0)
  expect(roads).toBeGreaterThan(0)
  expect(points).toBeGreaterThan(0)

  await page.getByTestId('feature-buildings').locator('input').uncheck()
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBe(0)
  expect(await renderedCount(page, { feat: 'traffic_roads' })).toBe(roads)

  await page.getByTestId('feature-points').locator('input').uncheck()
  await settle(page)
  expect(await renderedCount(page, { feat: 'points' })).toBe(0)

  await page.reload()
  await page.waitForFunction(() => (window as unknown as { __oreMap: any }).__oreMap?.isStyleLoaded())
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBe(0)
  expect(await renderedCount(page, { feat: 'points' })).toBe(0)

  await page.getByTestId('features-all').click()
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBe(buildings)
  expect(await renderedCount(page, { feat: 'points' })).toBe(points)
})

test('zoom thresholds switch the expected layers', async ({ page }) => {
  await openMap(page)
  const at = async (z: number) => {
    await jumpTo(page, z)
    return {
      dots: await renderedCount(page, { layers: ['osm-point-dot'] }),
      pins: await renderedCount(page, { layers: ['osm-pin'] }),
      addr: await renderedCount(page, { layers: ['osm-addr'] }),
      vertices: await renderedCount(page, { layers: ['osm-vertex'] }),
      lines: await renderedCount(page, { layers: ['osm-ground-stroke'] }),
      buildings: await renderedCount(page, { building: true }),
    }
  }
  const z14 = await at(14.5)
  expect(z14.dots).toBeGreaterThan(0)
  expect(z14.pins).toBe(0)
  expect(z14.vertices).toBe(0)
  expect(z14.lines).toBeGreaterThan(0)
  const z16 = await at(16)
  expect(z16.dots).toBe(0)
  expect(z16.pins).toBeGreaterThan(0)
  expect(z16.addr).toBeGreaterThan(0)
  expect(z16.vertices).toBeGreaterThan(0)
  for (const z of [12, 13, 15, 17, 18]) {
    const r = await at(z)
    expect(r.lines, `lines at ${z}`).toBeGreaterThan(0)
  }
  const z19 = await at(19)
  expect(z19.buildings).toBeGreaterThan(0)
  expect(z19.lines).toBeGreaterThan(0)
})

test('fast pans and zooms keep the map drawn', async ({ page }) => {
  await openMap(page, { zoom: 16 })
  for (let i = 0; i < 6; i++) {
    const dx = i % 2 ? 300 : -300
    await page.evaluate((dx) => (window as unknown as { __oreMap: any }).__oreMap.panBy([dx, dx / 3], { duration: 150 }), dx)
    await page.waitForTimeout(80)
  }
  await page.evaluate(() => (window as unknown as { __oreMap: any }).__oreMap.zoomTo(13, { duration: 300 }))
  await page.waitForTimeout(350)
  await page.evaluate(([c]) => (window as unknown as { __oreMap: any }).__oreMap.flyTo({ center: c, zoom: 18, duration: 400 }), [CENTRE] as const)
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBeGreaterThan(0)
})

test('quick filter highlights draw and clear', async ({ page }) => {
  await openMap(page)
  await panels.tab(page, 'map').click()
  const hlLayers = () =>
    page.evaluate(() => {
      const m = (window as unknown as { __oreMap: any }).__oreMap
      return m.getStyle().layers.filter((l: any) => l.id.startsWith('ore-hl-')).map((l: any) => l.id)
    })
  expect(await hlLayers()).toEqual([])
  await page.getByTestId('quick-filter').fill('building=*')
  await expect.poll(async () => (await hlLayers()).length).toBeGreaterThan(0)
  await settle(page)
  const hl = await page.evaluate(() => {
    const m = (window as unknown as { __oreMap: any }).__oreMap
    const ids = m.getStyle().layers.filter((l: any) => l.id.startsWith('ore-hl-') && l.id !== 'ore-hl-dim').map((l: any) => l.id)
    return m.queryRenderedFeatures({ layers: ids }).length
  })
  expect(hl).toBeGreaterThan(0)
  expect(await renderedCount(page, { building: true })).toBeGreaterThan(0)
  await page.getByTestId('quick-filter').fill('')
  await expect.poll(async () => (await hlLayers()).length).toBe(0)
})

test('hovering features shows no popup', async ({ page }) => {
  await openMap(page, { zoom: 17 })
  const box = await panels.map(page).boundingBox()
  for (let i = 0; i <= 40; i++) {
    await page.mouse.move(box!.x + 320 + i * 20, box!.y + 120 + (i % 7) * 40)
    await page.waitForTimeout(30)
    await expect(page.getByTestId('map-popup')).toHaveCount(0)
  }
  await page.waitForTimeout(1000)
  await expect(page.getByTestId('map-popup')).toHaveCount(0)
})

test('imagery on and off keeps the data drawn', async ({ page }) => {
  await page.route(/tile\.openstreetmap\.org|\/imagery\//, (r) => r.fulfill({ status: 204 }))
  await openMap(page)
  const buildings = await renderedCount(page, { building: true })
  const select = panels.tab(page, 'map')
  await select.click()
  const options = await page.locator('select').first().locator('option').count()
  test.skip(options < 2, 'no imagery sources configured')
  await page.locator('select').first().selectOption({ index: 1 })
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBe(buildings)
  await page.locator('select').first().selectOption({ index: 0 })
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBe(buildings)
})

test('config tabs switch without disturbing the map', async ({ page }) => {
  await openMap(page)
  const buildings = await renderedCount(page, { building: true })
  for (const tab of ['validate', 'rules', 'presets', 'map'] as const) {
    const c = await captureTransition(page, () => panels.tab(page, tab).click(), 400)
    expectSteady(c, `tab ${tab}`)
    expect(await renderedCount(page, { building: true })).toBe(buildings)
  }
})
