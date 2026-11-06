import type { Locator, Page } from '@playwright/test'
import { panels } from './panels'
import { expect, openMap, settle, test } from './helpers'

type MapWindow = Window & { __oreMap: any }

async function luminance(el: Locator): Promise<number> {
  const bg = await el.evaluate((e) => getComputedStyle(e).backgroundColor)
  const [r, g, b] = (bg.match(/[\d.]+/g) ?? ['255', '255', '255']).slice(0, 3).map(Number)
  const lin = (c: number) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}

async function mapState(page: Page) {
  return page.evaluate(async () => {
    const m = (window as unknown as MapWindow).__oreMap
    const style = m.getStyle()
    const c = m.getCanvas() as HTMLCanvasElement
    const probe = document.createElement('canvas')
    probe.width = 64
    probe.height = 64
    const ctx = probe.getContext('2d')!
    await new Promise<void>((resolve) => {
      m.once('render', () => {
        ctx.drawImage(c, 0, 0, 64, 64)
        resolve()
      })
      m.triggerRepaint()
    })
    const px = ctx.getImageData(0, 0, 64, 64).data
    let sum = 0
    for (let i = 0; i < px.length; i += 4) sum += (px[i] + px[i + 1] + px[i + 2]) / 3
    return {
      layers: style.layers.map((l: { id: string; paint?: unknown; layout?: unknown }) => JSON.stringify([l.id, l.paint ?? null, l.layout ?? null])),
      canvas: `${c.width}x${c.height}`,
      brightness: sum / (px.length / 4) / 255,
    }
  })
}

async function selectFeature(page: Page) {
  const [x, y] = await page.evaluate(() => {
    const m = (window as unknown as MapWindow).__oreMap
    const r = m.getContainer().getBoundingClientRect()
    for (let py = 120; py < r.height - 320; py += 8) {
      for (let px = 120; px < r.width - 420; px += 8) {
        const top = m.queryRenderedFeatures([px, py], { layers: ['osm-fill'] })[0]
        if (top && top.properties['@bld'] === true) return [px + r.left, py + r.top]
      }
    }
    throw new Error('no building in view')
  })
  await page.mouse.click(x, y)
  await expect(panels.leftPanel(page)).toHaveCSS('width', '392px')
}

test.describe('dark UI', () => {
  test.use({ colorScheme: 'dark' })

  test('chrome is dark and the map stays light', async ({ page, browser }) => {
    await openMap(page, { zoom: 17.5 })
    await expect(page.locator('html')).toHaveClass(/dark/)
    const dark = await mapState(page)
    expect(dark.brightness, 'map canvas is light').toBeGreaterThan(0.6)
    await selectFeature(page)
    await settle(page)
    const chrome: [string, Locator][] = [
      ['top bar', panels.topBar(page)],
      ['left panel', panels.leftPanel(page)],
      ['right panel', panels.rightPanel(page)],
      ['bottom drawer', panels.bottomPanel(page).locator('section').first()],
    ]
    for (const [label, el] of chrome) expect(await luminance(el), `${label} background`).toBeLessThan(0.05)

    const ctx = await browser.newContext({ colorScheme: 'light', viewport: { width: 1440, height: 900 }, baseURL: test.info().project.use.baseURL })
    const light = await ctx.newPage()
    await openMap(light, { zoom: 17.5 })
    await expect(light.locator('html')).not.toHaveClass(/dark/)
    expect(await luminance(panels.topBar(light))).toBeGreaterThan(0.8)
    const lit = await mapState(light)
    expect(dark.layers).toEqual(lit.layers)
    expect(dark.canvas).toBe(lit.canvas)
    expect(Math.abs(dark.brightness - lit.brightness), 'map canvas brightness').toBeLessThan(0.03)
    await ctx.close()
  })

  test('settings switch between system, light and dark', async ({ page }) => {
    await openMap(page)
    await page.getByTitle('Settings').click()
    await page.getByTestId('settings-theme-light').click()
    await expect(page.locator('html')).not.toHaveClass(/dark/)
    await page.getByTestId('settings-theme-system').click()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await page.getByTestId('settings-theme-light').click()
    await page.reload()
    await expect(page.locator('html')).not.toHaveClass(/dark/)
  })
})
