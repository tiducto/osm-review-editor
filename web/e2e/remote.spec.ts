import { panels } from './panels'
import { expect, openMap, test } from './helpers'

const RC = `http://127.0.0.1:${process.env.E2E_RC_PORT ?? '8890'}`

test('remote-control import waits for confirmation and Cancel fetches nothing', async ({ page }) => {
  await openMap(page, { zoom: 17, storage: { 'ore.locale': 'cs' } })
  const before = await (await page.request.get('/api/drafts')).json()

  const refused = await page.request.get(`${RC}/import?url=${encodeURIComponent('http://127.0.0.1:3000/api/drafts')}`)
  expect(refused.status()).toBe(400)
  expect(await refused.text()).toContain('loopback')
  await expect(panels.remoteConfirm(page)).toHaveCount(0)

  const url = 'http://93.184.215.14/survey.osm?bbox=17.9,49.9,17.91,49.91'
  const pending = page.request.get(`${RC}/import?url=${encodeURIComponent(url)}`)
  await expect(panels.remoteConfirm(page)).toBeVisible()
  await expect(panels.dialog(page)).toHaveAttribute('aria-label', 'Načíst data z adresy URL?')
  await expect(panels.remoteUrl(page)).toHaveText(url)
  await expect(panels.remoteHost(page)).toHaveText('93.184.215.14')
  await expect(panels.remoteLoad(page)).toHaveText('Načíst')
  await panels.remoteCancel(page).click()
  const response = await pending
  expect(response.status()).toBe(403)
  await expect(panels.remoteConfirm(page)).toHaveCount(0)
  expect((await (await page.request.get('/api/drafts')).json()).length).toBe(before.length)
})

test('remote-control tags and data need confirmation before a draft appears', async ({ page }) => {
  await openMap(page, { zoom: 17, storage: { 'ore.locale': 'en' } })

  const tags = page.request.get(`${RC}/add_node?lat=49.938&lon=17.9052&addtags=${encodeURIComponent('amenity=bench|backrest=yes')}`)
  await expect(panels.remoteConfirm(page)).toHaveAttribute('data-command', 'add_node')
  await expect(panels.dialog(page)).toHaveAttribute('aria-label', 'Add a node from remote control?')
  await expect(panels.remoteTags(page)).toContainText('amenity=bench')
  await expect(panels.remoteTags(page)).toContainText('backrest=yes')
  await expect(panels.remoteLoad(page)).toHaveText('Create draft')
  await page.keyboard.press('Escape')
  expect((await tags).status()).toBe(403)
  await expect(panels.remoteConfirm(page)).toHaveCount(0)

  const data = '<osm version="0.6"><node id="-1" lat="49.938" lon="17.9052"><tag k="amenity" v="bench"/></node></osm>'
  const loaded = page.request.post(`${RC}/load_data`, { form: { data, layer_name: 'e2e remote bench' } })
  await expect(panels.remoteConfirm(page)).toHaveAttribute('data-command', 'load_data')
  await expect(panels.dialog(page)).toHaveAttribute('aria-label', 'Load data sent by another program?')
  await panels.remoteLoad(page).click()
  expect((await loaded).status()).toBe(200)
  await expect(panels.leaveReview(page)).toBeVisible()
  const drafts = await (await page.request.get('/api/drafts')).json()
  const draft = drafts.find((d: { title: string }) => d.title === 'e2e remote bench')
  expect(draft).toBeTruthy()
  await panels.leaveReview(page).click()
  await page.request.patch(`/api/drafts/${draft.id}`, { data: { status: 'closed' } })
})
