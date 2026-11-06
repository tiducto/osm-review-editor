import type { Page } from '@playwright/test'
import { panels } from './panels'
import { expect, openMap, settle, test, visibleMapRect } from './helpers'

type LngLat = [number, number]
type MapWindow = Window & { __oreMap: any }

const d = 0.0002

function osm([lon, lat]: LngLat) {
  return (
    `<?xml version="1.0"?><osm version="0.6">` +
    `<node id="9001" version="1" lat="${lat}" lon="${lon}"/>` +
    `<node id="9002" version="1" lat="${lat}" lon="${lon + d}"/>` +
    `<node id="9003" version="1" lat="${lat + d}" lon="${lon + d}"/>` +
    `<node id="9004" version="1" lat="${lat - d / 2}" lon="${lon - d}"><tag k="amenity" v="bench"/></node>` +
    `<way id="9100" version="1"><nd ref="9001"/><nd ref="9002"/><nd ref="9003"/><tag k="highway" v="footway"/></way>` +
    `</osm>`
  )
}

async function lngLatAt(page: Page, x: number, y: number): Promise<LngLat> {
  return page.evaluate(([x, y]) => {
    const m = (window as unknown as MapWindow).__oreMap
    const r = m.getContainer().getBoundingClientRect()
    const l = m.unproject([x - r.left, y - r.top])
    return [l.lng, l.lat] as LngLat
  }, [x, y] as const)
}

async function screenPoint(page: Page, ll: LngLat) {
  return page.evaluate((ll) => {
    const m = (window as unknown as MapWindow).__oreMap
    const r = m.getContainer().getBoundingClientRect()
    const p = m.project(ll)
    return { x: r.left + p.x, y: r.top + p.y }
  }, ll)
}

async function centreInVisibleMap(page: Page, ll: LngLat) {
  const rect = await visibleMapRect(page)
  await page.evaluate(
    ([ll, vx, vy]) => {
      const m = (window as unknown as MapWindow).__oreMap
      const r = m.getContainer().getBoundingClientRect()
      const p = m.project(ll)
      m.jumpTo({ center: m.unproject([r.width / 2 + p.x - (vx - r.left), r.height / 2 + p.y - (vy - r.top)]) })
    },
    [ll, rect.x + rect.w / 2, rect.y + rect.h / 2] as const,
  )
  await settle(page)
}

async function enterEditor(page: Page, touch: boolean): Promise<LngLat> {
  await openMap(page, { zoom: 18.5, storage: { 'ore.locale': 'en' } })
  const rect = await visibleMapRect(page)
  const anchor = await lngLatAt(page, rect.x + rect.w / 2, rect.y + rect.h / 2)
  await page.route('**/api/osm/map**', (r) => r.fulfill({ status: 200, contentType: 'application/xml', body: osm(anchor) }))
  if (touch) await panels.editEnter(page).tap()
  else await panels.editEnter(page).click()
  await expect(panels.editExit(page)).toBeVisible()
  await settle(page)
  await centreInVisibleMap(page, anchor)
  return anchor
}

const existingNode = (page: Page) => page.getByText(/^n9001\b/)
const presetPicker = (page: Page) => page.getByRole('textbox', { name: 'Search feature types' })

test.describe('phone with touch', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

  test('add point places a new point when tapping next to an existing node', async ({ page }) => {
    const anchor = await enterEditor(page, true)
    const node = await screenPoint(page, anchor)

    await panels.editTool(page, 'add-point').tap()
    await expect(panels.editTool(page, 'add-point')).toHaveClass(/active/)
    await page.touchscreen.tap(node.x + 4, node.y + 4)
    await expect(panels.editCount(page)).toHaveText(/1/)
    await expect(existingNode(page)).toHaveCount(0)
    await expect(presetPicker(page)).toBeVisible()
    await expect(panels.editUndo(page)).toHaveAttribute('title', /Added a point/)

    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
    await page.keyboard.press('1')
    await expect(panels.editTool(page, 'add-point')).toHaveClass(/active/)
    await page.touchscreen.tap(node.x, node.y)
    await expect(panels.editCount(page)).toHaveText(/2/)
    await expect(existingNode(page)).toHaveCount(0)

    await panels.editUndo(page).tap()
    await expect(panels.editCount(page)).toHaveText(/1/)
    await panels.editUndo(page).tap()
    await expect(panels.editCount(page)).toHaveText(/0/)
  })

  test('add line and add area draw on taps, starting from an existing node', async ({ page }) => {
    const anchor = await enterEditor(page, true)
    const n = await screenPoint(page, anchor)

    await panels.editTool(page, 'add-line').tap()
    await page.touchscreen.tap(n.x + 3, n.y + 3)
    await expect(existingNode(page)).toHaveCount(0)
    await expect(panels.editCount(page)).toHaveText(/1/)
    await page.waitForTimeout(400)
    await page.touchscreen.tap(n.x - 40, n.y + 60)
    await page.waitForTimeout(400)
    await page.touchscreen.tap(n.x - 40, n.y + 60)
    await expect(panels.editTool(page, 'add-line')).not.toHaveClass(/active/)
    await expect(presetPicker(page)).toBeVisible()
    await expect(existingNode(page)).toHaveCount(0)

    await panels.editTool(page, 'add-area').tap()
    await expect(panels.editTool(page, 'add-area')).toHaveClass(/active/)
    for (const [dx, dy] of [[30, 40], [70, 40], [70, 80]]) {
      await page.touchscreen.tap(n.x + dx, n.y + dy)
      await page.waitForTimeout(400)
    }
    await page.touchscreen.tap(n.x + 70, n.y + 80)
    await expect(panels.editCount(page)).toHaveText(/2/)
    await expect(panels.editTool(page, 'add-area')).not.toHaveClass(/active/)
    await expect(presetPicker(page)).toBeVisible()
  })
})

test('desktop: add point on an existing node still selects it like iD', async ({ page }) => {
  const anchor = await enterEditor(page, false)
  const node = await screenPoint(page, anchor)
  await panels.editTool(page, 'add-point').click()
  await page.mouse.move(node.x + 2, node.y + 2)
  await page.mouse.click(node.x + 2, node.y + 2)
  await expect(existingNode(page).first()).toBeVisible()
  await expect(panels.editCount(page)).toHaveText(/0/)
})
