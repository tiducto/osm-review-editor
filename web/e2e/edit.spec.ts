import { panels } from './panels'
import { captureTransition, expect, expectSteady, openMap, renderedCount, settle, test } from './helpers'

test('edit mode: enter, draw a point, undo, exit', async ({ page }) => {
  await page.route('**/api/osm/map**', (r) => r.fulfill({ status: 200, contentType: 'application/xml', body: '<?xml version="1.0"?><osm version="0.6"></osm>' }))
  await openMap(page, { zoom: 17 })
  const buildings = await renderedCount(page, { building: true })

  const enter = await captureTransition(page, () => page.getByTestId('edit-enter').click(), 1500)
  expectSteady(enter, 'edit enter')
  await expect(page.getByTestId('edit-exit')).toBeVisible()
  await settle(page)
  expect(await renderedCount(page, { building: true })).toBe(buildings)

  await page.getByTestId('edit-add-point').click()
  const box = await panels.map(page).boundingBox()
  await page.mouse.click(box!.x + box!.width * 0.55, box!.y + box!.height * 0.3)
  await expect(page.getByTestId('edit-count')).toHaveText(/1/)
  await page.getByTestId('edit-undo').click()
  await expect(page.getByTestId('edit-count')).toHaveText(/0/)

  const exit = await captureTransition(page, () => page.getByTestId('edit-exit').click(), 800)
  expectSteady(exit, 'edit exit')
  await expect(page.getByTestId('edit-enter')).toBeVisible()
  expect(await renderedCount(page, { building: true })).toBe(buildings)
})
