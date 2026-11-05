import { panels } from './panels'
import { CENTRE, captureTransition, expect, expectSteady, openMap, renderedCount, settle, test } from './helpers'

test('review opens and closes without blank frames', async ({ page }) => {
  const [lon, lat] = CENTRE
  const osmChange = `<osmChange version="0.6"><create><node id="-1" lat="${lat}" lon="${lon}" version="0"><tag k="amenity" v="bench"/></node></create><modify/><delete if-unused="true"/></osmChange>`
  const res = await page.request.post('/api/drafts/from-osmchange', { data: { title: 'e2e bench', osmChange } })
  expect(res.status()).toBe(201)

  await openMap(page, { zoom: 17 })
  const buildings = await renderedCount(page, { building: true })
  await panels.paneButton(page, 'drafts').click()
  await panels.draftReview(page).click()
  await expect(panels.leaveReview(page)).toBeVisible()
  await settle(page)
  const changes = await page.evaluate(() => {
    const m = (window as unknown as { __oreMap: any }).__oreMap
    return m.queryRenderedFeatures({ layers: ['ore-changes-point'] }).length
  })
  expect(changes).toBeGreaterThan(0)
  expect(await renderedCount(page, { building: true })).toBeGreaterThan(0)

  const close = await captureTransition(page, () => panels.leaveReview(page).click())
  expectSteady(close, 'review close')
  await expect(panels.leaveReview(page)).toHaveCount(0)
  expect(buildings).toBeGreaterThan(0)
})
