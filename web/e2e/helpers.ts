import { expect, test as base, type Page } from '@playwright/test'

export const CENTRE: [number, number] = [17.9052, 49.938]

type MapWindow = Window & { __oreMap: any }

export const test = base.extend<{ errors: string[] }>({
  errors: [
    async ({ page }, use) => {
      const errors: string[] = []
      page.on('console', (m) => {
        if (m.type() === 'error') errors.push(m.text())
        else if (m.type() === 'warning' && /could not be loaded|already exists|Style is not done loading/.test(m.text())) errors.push(m.text())
      })
      page.on('pageerror', (e) => errors.push(String(e)))
      await use(errors)
      expect(errors, 'console errors').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }

export async function openMap(page: Page, opts: { center?: [number, number]; zoom?: number; storage?: Record<string, string> } = {}) {
  const view = { c: opts.center ?? CENTRE, z: opts.zoom ?? 16.5 }
  await page.addInitScript(
    ([view, storage]) => {
      if (sessionStorage.getItem('ore-e2e-init')) return
      sessionStorage.setItem('ore-e2e-init', '1')
      localStorage.setItem('ore.view', JSON.stringify(view))
      for (const [k, v] of Object.entries(storage)) localStorage.setItem(k, v)
    },
    [view, opts.storage ?? {}] as const,
  )
  await page.goto('/')
  await page.waitForFunction(() => {
    const m = (window as unknown as MapWindow).__oreMap
    return !!m && m.isStyleLoaded()
  })
  await settle(page)
}

export async function settle(page: Page) {
  await page.waitForFunction(
    () =>
      new Promise<boolean>((resolve) => {
        const m = (window as unknown as MapWindow).__oreMap
        let n = 0
        const tick = () => {
          if (m.loaded() && m.areTilesLoaded() && !m.isMoving()) {
            if (++n >= 3) return resolve(true)
          } else n = 0
          setTimeout(tick, 100)
        }
        tick()
      }),
    undefined,
    { timeout: 30_000 },
  )
}

export async function jumpTo(page: Page, zoom: number, center: [number, number] = CENTRE) {
  await page.evaluate(([c, z]) => (window as unknown as MapWindow).__oreMap.jumpTo({ center: c, zoom: z }), [center, zoom] as const)
  await settle(page)
}

export async function renderedCount(page: Page, opts: { building?: boolean; layers?: string[]; feat?: string } = {}) {
  return page.evaluate((opts) => {
    const m = (window as unknown as MapWindow).__oreMap
    const layers = opts.layers?.filter((l) => m.getLayer(l))
    if (opts.layers && !layers?.length) return 0
    const fs = m.queryRenderedFeatures(layers ? { layers } : undefined).filter((f: any) => {
      if (f.source !== 'ore-osm') return false
      if (opts.building && f.properties['@bld'] !== true) return false
      if (opts.feat && f.properties['@feat'] !== opts.feat) return false
      return true
    })
    return new Set(fs.map((f: any) => `${f.sourceLayer}:${f.id}`)).size
  }, opts)
}

export interface Rect {
  x: number
  y: number
  w: number
  h: number
}

export async function visibleMapRect(page: Page): Promise<Rect> {
  return page.evaluate(() => {
    const root = document.querySelector('.ore-map') as HTMLElement
    const map = root.getBoundingClientRect()
    const css = getComputedStyle(root)
    const inset = (name: string) => Number.parseFloat(css.getPropertyValue(name)) || 0
    const left = map.left + inset('--ore-l')
    const right = map.right - inset('--ore-r')
    const top = map.top + inset('--ore-t')
    const bottom = map.bottom - inset('--ore-b')
    return { x: Math.ceil(left), y: Math.ceil(top), w: Math.floor(right - left), h: Math.floor(bottom - top) }
  })
}

function intersect(a: Rect, b: Rect): Rect {
  const x = Math.max(a.x, b.x)
  const y = Math.max(a.y, b.y)
  return { x, y, w: Math.min(a.x + a.w, b.x + b.w) - x, h: Math.min(a.y + a.h, b.y + b.h) - y }
}

export interface Capture {
  ratios: number[]
  drift: number
  canvasSizes: number
  frames: number
}

export async function captureTransition(page: Page, action: () => Promise<unknown>, ms = 700): Promise<Capture> {
  const before = await visibleMapRect(page)
  const cdp = await page.context().newCDPSession(page)
  const frames: string[] = []
  cdp.on('Page.screencastFrame', (f) => {
    frames.push(f.data)
    void cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => undefined)
  })
  const probes = [
    [before.x + before.w * 0.5, before.y + before.h * 0.3],
    [before.x + before.w * 0.7, before.y + before.h * 0.2],
    [before.x + before.w * 0.4, before.y + 40],
  ]
  await page.evaluate(
    ([probes, ms]) => {
      const w = window as unknown as MapWindow & { __probe?: Promise<{ drift: number; sizes: number }> }
      const m = w.__oreMap
      const at = () => {
        const r = m.getContainer().getBoundingClientRect()
        return probes.map(([x, y]: number[]) => m.unproject([x - r.left, y - r.top]))
      }
      const start = at()
      const sizes = new Set<string>()
      let drift = 0
      w.__probe = new Promise((resolve) => {
        const t0 = performance.now()
        const loop = () => {
          sizes.add(`${m.getCanvas().width}x${m.getCanvas().height}`)
          at().forEach((p: any, i: number) => {
            drift = Math.max(drift, Math.abs(p.lng - start[i].lng), Math.abs(p.lat - start[i].lat))
          })
          if (performance.now() - t0 < ms) requestAnimationFrame(loop)
          else resolve({ drift, sizes: sizes.size })
        }
        requestAnimationFrame(loop)
      })
    },
    [probes, ms + 600] as const,
  )
  await cdp.send('Page.startScreencast', { format: 'png', everyNthFrame: 1 })
  await page.waitForTimeout(120)
  await action()
  await page.waitForTimeout(ms)
  await settle(page)
  await page.waitForTimeout(200)
  await cdp.send('Page.stopScreencast')
  await cdp.detach()
  const probe = await page.evaluate(() => (window as unknown as { __probe: Promise<{ drift: number; sizes: number }> }).__probe)
  const after = await visibleMapRect(page)
  const rect = intersect(before, after)
  const ratios = await page.evaluate(
    async ([frames, rect]) => {
      const out: number[] = []
      for (const b64 of frames) {
        const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
        const bmp = await createImageBitmap(new Blob([bin], { type: 'image/png' }))
        const c = new OffscreenCanvas(bmp.width, bmp.height)
        const g = c.getContext('2d')!
        g.drawImage(bmp, 0, 0)
        const d = g.getImageData(rect.x, rect.y, rect.w, rect.h).data
        let bg = 0
        let n = 0
        for (let i = 0; i < d.length; i += 16) {
          n++
          const near = (r: number, gg: number, b: number) => Math.abs(d[i] - r) < 5 && Math.abs(d[i + 1] - gg) < 5 && Math.abs(d[i + 2] - b) < 5
          if (near(0xf2, 0xf2, 0xf2) || near(0xf4, 0xf5, 0xf7) || near(255, 255, 255)) bg++
        }
        out.push(1 - bg / n)
      }
      return out
    },
    [frames, rect] as const,
  )
  return { ratios, drift: probe.drift, canvasSizes: probe.sizes, frames: frames.length }
}

export function expectSteady(c: Capture, label: string) {
  expect(c.frames, `${label}: frames captured`).toBeGreaterThan(1)
  const first = c.ratios[0]
  expect(first, `${label}: map has content`).toBeGreaterThan(0.05)
  const worst = Math.min(...c.ratios)
  expect(worst, `${label}: no blank or flashing frame (ratios ${c.ratios.map((r) => r.toFixed(2)).join(' ')})`).toBeGreaterThan(first * 0.8)
  expect(c.drift, `${label}: map content stays still`).toBeLessThan(1e-7)
  expect(c.canvasSizes, `${label}: canvas never resized`).toBe(1)
}
