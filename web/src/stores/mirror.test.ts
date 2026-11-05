import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AppStatus } from '@/generated/models'
import { useAppStore } from './app'
import { useMirrorStore } from './mirror'

function status(mirror: Record<string, unknown>): AppStatus {
  return {
    version: 'test',
    mirror: { available: true, file: 'a.gol', openedAt: null, dataTimestamp: null, message: null, sidecar: null, ...mirror },
    account: { target: 'dev', connected: false, displayName: null, configured: false },
    mcpUrl: '',
    remoteControlPort: 8111,
  } as unknown as AppStatus
}

const ago = (s: number) => new Date(Date.now() - s * 1000).toISOString()

describe('mirror store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('rates lag from the data time when no lag is reported', () => {
    const app = useAppStore()
    const m = useMirrorStore()
    app.status = status({ dataTimestamp: ago(120) })
    expect(m.health).toBe('ok')
    expect(m.isFileTime).toBe(true)
    app.status = status({ dataTimestamp: ago(3600) })
    expect(m.health).toBe('behind')
    app.status = status({ dataTimestamp: ago(5 * 3600) })
    expect(m.health).toBe('stale')
  })

  it('prefers the sequence, OSM time and lag from the live status event', () => {
    const app = useAppStore()
    const m = useMirrorStore()
    app.status = status({ dataTimestamp: ago(5 * 3600) })
    m.onStatusEvent({ type: 'mirror_status', dataSequence: 7012345, dataTimestamp: ago(60), lagSeconds: 60, state: 'updating' })
    expect(m.sequence).toBe(7012345)
    expect(m.isFileTime).toBe(false)
    expect(m.health).toBe('ok')
    expect(m.busy).toBe(true)
  })

  it('is stale when the last cycle failed', () => {
    const app = useAppStore()
    const m = useMirrorStore()
    app.status = status({ dataTimestamp: ago(60), sidecar: { result: 'failed', error: 'gol update failed' } })
    expect(m.health).toBe('stale')
    expect(m.lastError).toBe('gol update failed')
  })
})
