import type { Page } from '@playwright/test'
import { panels } from './panels'
import { CENTRE, captureTransition, expect, expectSteady, openMap, renderedCount, settle, test } from './helpers'

test('review opens and closes without blank frames', async ({ page }) => {
  const [lon, lat] = CENTRE
  const osmChange = `<osmChange version="0.6"><create><node id="-1" lat="${lat}" lon="${lon}" version="0"><tag k="amenity" v="bench"/></node></create><modify/><delete if-unused="true"/></osmChange>`
  const res = await page.request.post('/api/drafts/from-osmchange', { data: { title: 'e2e bench', osmChange } })
  expect(res.status()).toBe(201)

  await openMap(page, { zoom: 17 })
  const buildings = await renderedCount(page, { building: true })
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

type Road = { type: string; id: number; fid: number; sourceLayer: string; x: number; y: number; lng: number; lat: number }

async function roadsAt(page: Page, ids: number[] | null, overlay: string | null): Promise<Road[]> {
  return page.evaluate(
    ([ids, overlay]) => {
      const m = (window as unknown as { __oreMap: any }).__oreMap
      const canvas = m.getCanvas().getBoundingClientRect()
      const root = document.querySelector('.ore-map') as HTMLElement
      const css = getComputedStyle(root)
      const inset = (n: string) => Number.parseFloat(css.getPropertyValue(n)) || 0
      const r = root.getBoundingClientRect()
      const view = { l: r.left + inset('--ore-l') + 40, r: r.right - inset('--ore-r') - 40, t: r.top + inset('--ore-t') + 40, b: r.bottom - inset('--ore-b') - 40 }
      const cx = (view.l + view.r) / 2
      const cy = (view.t + view.b) / 2
      const osmLayers = ['osm-pin', 'osm-addr', 'osm-vertex', 'osm-point-dot', 'osm-line-hit'].filter((l) => m.getLayer(l))
      const best = new Map<number, { type: string; id: number; fid: number; sourceLayer: string; x: number; y: number; lng: number; lat: number; d: number }>()
      for (const f of m.queryRenderedFeatures({ layers: ['osm-line-hit'] })) {
        const p = f.properties
        const id = Number(p['@id'])
        if (p['@feat'] !== 'traffic_roads' || p['@type'] !== 'way' || typeof f.id !== 'number') continue
        if (ids && !ids.includes(id)) continue
        const lines = f.geometry.type === 'LineString' ? [f.geometry.coordinates] : f.geometry.type === 'MultiLineString' ? f.geometry.coordinates : []
        for (const line of lines) {
          for (let i = 0; i + 1 < line.length; i++) {
            const a = m.project(line[i])
            const b = m.project(line[i + 1])
            if (Math.hypot(b.x - a.x, b.y - a.y) < 30) continue
            const px = (a.x + b.x) / 2
            const py = (a.y + b.y) / 2
            const x = px + canvas.left
            const y = py + canvas.top
            if (x < view.l || x > view.r || y < view.t || y > view.b) continue
            const box = [
              [px - 6, py - 6],
              [px + 6, py + 6],
            ]
            const hits = m.queryRenderedFeatures(box, { layers: osmLayers })
            if (!hits.every((h: any) => h.layer.type === 'line' && h.id === f.id)) continue
            if (overlay && !m.queryRenderedFeatures([px, py], { layers: [overlay] }).length) continue
            const d = Math.hypot(x - cx, y - cy)
            const prev = best.get(id)
            const ll = m.unproject([px, py])
            if (!prev || d < prev.d) best.set(id, { type: 'way', id, fid: f.id, sourceLayer: f.sourceLayer, x, y, lng: ll.lng, lat: ll.lat, d })
          }
        }
      }
      return [...best.values()].sort((a, b) => a.d - b.d).map(({ d: _d, ...rest }) => rest)
    },
    [ids, overlay] as const,
  )
}

async function hovered(page: Page, road: Road): Promise<boolean> {
  return page.evaluate((r) => {
    const m = (window as unknown as { __oreMap: any }).__oreMap
    return m.getFeatureState({ source: 'ore-osm', sourceLayer: r.sourceLayer, id: r.fid }).hover === true
  }, road)
}

test('hover highlights the road under the pointer while review overlays cover it', async ({ page }) => {
  const ruleId = 'e2e-context'
  await openMap(page, { zoom: 18 })
  const roads = await roadsAt(page, null, null)
  expect(roads.length).toBeGreaterThanOrEqual(3)
  const [changed, ctxA, ctxB] = roads
  const ref = (r: Road) => ({ type: r.type, id: r.id })

  const osmChange = `<osmChange version="0.6"><create><node id="-1" lat="${changed.lat}" lon="${changed.lng}" version="0"><tag k="highway" v="crossing"/></node></create><modify/><delete if-unused="true"/></osmChange>`
  const created = await page.request.post('/api/drafts/from-osmchange', { data: { title: 'e2e hover', osmChange } })
  expect(created.status()).toBe(201)
  const detail = await created.json()
  const draftId: string = detail.draft.draft.id
  const nodeRef = detail.draft.changes[0].ref

  await page.route(`**/api/drafts/${draftId}`, async (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    const res = await route.fetch()
    const body = await res.json()
    for (const c of body.changes) c.ruleId = ruleId
    await route.fulfill({ response: res, json: body })
  })
  await page.route('**/api/rules', async (route) => {
    const res = await route.fetch()
    const list = await res.json()
    list.push({ id: ruleId, title: 'E2E context', description: '', severity: 'warning', confidence: 'needs_review', hasFix: true, enabled: true })
    await route.fulfill({ response: res, json: list })
  })
  await page.route(`**/api/rules/${ruleId}/issues`, (route) =>
    route.fulfill({
      json: {
        ruleId,
        issueCount: 1,
        truncated: false,
        tookMs: 1,
        issues: [
          {
            ruleId,
            ref: nodeRef,
            tags: {},
            centroid: { lng: changed.lng, lat: changed.lat },
            severity: 'warning',
            fix: null,
            confidence: 'needs_review',
            context: [ref(ctxA), ref(ctxB)],
            note: null,
          },
        ],
      },
    }),
  )
  await page.reload()
  await page.waitForFunction(() => (window as unknown as { __oreMap: any }).__oreMap?.isStyleLoaded())
  await settle(page)

  await panels.draftRowReview(page, draftId).click()
  await expect(panels.leaveReview(page)).toBeVisible()
  await expect
    .poll(() => page.evaluate(() => (window as unknown as { __oreMap: any }).__oreMap.queryRenderedFeatures({ layers: ['ore-context-line'] }).length as number))
    .toBeGreaterThan(0)
  await settle(page)

  const [onA] = await roadsAt(page, [ctxA.id], 'ore-context-line')
  const [onB] = await roadsAt(page, [ctxB.id], 'ore-context-line')
  expect(onA, 'context road A has a clear point on screen').toBeTruthy()
  expect(onB, 'context road B has a clear point on screen').toBeTruthy()
  const onChanged = await page.evaluate(
    ([road, lng, lat]) => {
      const m = (window as unknown as { __oreMap: any }).__oreMap
      const canvas = m.getCanvas().getBoundingClientRect()
      const p = m.project([lng, lat])
      const change = m.queryRenderedFeatures([p.x, p.y], { layers: ['ore-changes-point'] }).length
      return { ...road, x: p.x + canvas.left, y: p.y + canvas.top, change }
    },
    [changed, changed.lng, changed.lat] as const,
  )
  expect(onChanged.change, 'the new node change sits on the road').toBeGreaterThan(0)

  await page.mouse.move(onA.x, onA.y)
  await expect.poll(() => hovered(page, onA)).toBe(true)

  await page.mouse.move(onB.x, onB.y, { steps: 8 })
  await expect.poll(() => hovered(page, onB)).toBe(true)
  expect(await hovered(page, onA)).toBe(false)

  await page.mouse.move(onChanged.x, onChanged.y, { steps: 8 })
  await expect.poll(() => hovered(page, onChanged)).toBe(true)
  expect(await hovered(page, onB)).toBe(false)

  await panels.leaveReview(page).click()
  await page.request.patch(`/api/drafts/${draftId}`, { data: { status: 'closed' } })
})
