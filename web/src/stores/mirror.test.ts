import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AppStatus, MirrorStatus } from '@/generated/models'
import { useAppStore } from './app'
import { useMirrorStore } from './mirror'

function mirror(m: Record<string, unknown>): MirrorStatus {
  return {
    available: true, file: 'a.gol', openedAt: null, dataTimestamp: null, message: null, sidecar: null,
    dataSequence: null, dataSource: null, lagSeconds: null, replication: null, ...m,
  } as MirrorStatus
}

function status(m: Record<string, unknown>): AppStatus {
  return {
    version: 'test',
    mirror: mirror(m),
    account: { target: 'dev', connected: false, displayName: null, configured: false },
    mcpUrl: '',
    remoteControlPort: 8111,
  } as unknown as AppStatus
}

const ago = (s: number) => new Date(Date.now() - s * 1000).toISOString()

describe('mirror store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('rates lag from the OSM data time', () => {
    const app = useAppStore()
    const m = useMirrorStore()
    app.status = status({ dataTimestamp: ago(120), dataSequence: 7360230 })
    expect(m.health).toBe('ok')
    expect(m.isFileTime).toBe(false)
    expect(m.sequence).toBe(7360230)
    app.status = status({ dataTimestamp: ago(3600) })
    expect(m.health).toBe('behind')
    app.status = status({ dataTimestamp: ago(5 * 3600) })
    expect(m.health).toBe('stale')
  })

  it('falls back to the file time when the GOL has no replication info', () => {
    const app = useAppStore()
    const m = useMirrorStore()
    app.status = status({ openedAt: ago(60) })
    expect(m.isFileTime).toBe(true)
    expect(m.dataTime).not.toBeNull()
    expect(m.health).toBe('unknown')
  })

  it('takes the live mirror_status and reports a running cycle', () => {
    const app = useAppStore()
    const m = useMirrorStore()
    app.status = status({ dataTimestamp: ago(5 * 3600) })
    m.onStatus(mirror({ dataTimestamp: ago(60), dataSequence: 7012345, sidecar: { phase: 'updating' } }))
    expect(m.sequence).toBe(7012345)
    expect(m.health).toBe('ok')
    expect(m.busy).toBe(true)
    expect(m.sidecarBusy).toBe(true)
    expect(m.state).toBe('updating')
  })

  it('is stale when the last cycle failed', () => {
    const app = useAppStore()
    const m = useMirrorStore()
    app.status = status({ dataTimestamp: ago(60), sidecar: { result: 'failed', error: 'gol update failed' } })
    expect(m.health).toBe('stale')
    expect(m.lastError).toBe('gol update failed')
  })
})
