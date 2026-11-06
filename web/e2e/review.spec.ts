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

test('approving an untagged new node alone asks to approve its way', async ({ page }) => {
  const [lon, lat] = CENTRE
  const osmChange =
    `<osmChange version="0.6"><create>` +
    `<node id="-1" lat="${lat}" lon="${lon}" version="0"/>` +
    `<node id="-2" lat="${lat + 0.0003}" lon="${lon + 0.0004}" version="0"/>` +
    `<way id="-1" version="0"><nd ref="-1"/><nd ref="-2"/><tag k="highway" v="footway"/></way>` +
    `</create><modify/><delete if-unused="true"/></osmChange>`
  const res = await page.request.post('/api/drafts/from-osmchange', { data: { title: 'e2e orphan', osmChange } })
  expect(res.status()).toBe(201)
  const draftId: string = (await res.json()).draft.draft.id
  const statuses = async () => {
    const d = await (await page.request.get(`/api/drafts/${draftId}`)).json()
    return d.changes.map((c: { ref: { type: string }; status: string }) => `${c.ref.type}:${c.status}`).sort()
  }

  await openMap(page, { zoom: 17 })
  await panels.paneButton(page, 'drafts').click()
  await panels.draftRowReview(page, draftId).click()
  await expect(panels.reviewRef(page)).toBeVisible()
  for (let i = 0; i < 3 && !(await panels.reviewRef(page).textContent())?.startsWith('n'); i++) await panels.reviewSkip(page).click()
  await expect(panels.reviewRef(page)).toHaveText(/^n-\d+$/)

  await panels.reviewApprove(page).click()
  await expect(panels.dialog(page)).toHaveAttribute('aria-label', 'Approve the way too?')
  await expect(panels.cascadeMessage(page)).toContainText(/is a new node without tags that only w-\d+ uses/)
  await expect(panels.cascadeRefs(page)).toHaveText(/^\s*w-\d+\s*$/)
  await page.getByRole('button', { name: 'Cancel' }).click()
  await expect(panels.dialog(page)).toHaveCount(0)
  expect(await statuses()).toEqual(['node:pending', 'node:pending', 'way:pending'])

  await panels.reviewApprove(page).click()
  await page.getByRole('button', { name: 'Approve with the way' }).click()
  await expect.poll(statuses).toEqual(['node:approved', 'node:approved', 'way:approved'])

  const preview = await (await page.request.get(`/api/drafts/${draftId}/merge-preview`)).json()
  expect(preview.blocked).toEqual([])
  await panels.leaveReview(page).click()
  await page.request.patch(`/api/drafts/${draftId}`, { data: { status: 'closed' } })
})
