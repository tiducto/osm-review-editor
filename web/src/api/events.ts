import type { UiEvent } from '@/generated/models'

export type ConnectionState = 'connecting' | 'open' | 'closed'

export class EventStream {
  private ws: WebSocket | null = null
  private attempt = 0
  private timer: ReturnType<typeof setTimeout> | null = null
  private stopped = false

  constructor(
    private readonly onEvent: (e: UiEvent) => void,
    private readonly onState: (s: ConnectionState) => void,
  ) {}

  start(): void {
    this.stopped = false
    this.connect()
    document.addEventListener('visibilitychange', this.onVisible)
  }

  stop(): void {
    this.stopped = true
    if (this.timer) clearTimeout(this.timer)
    this.ws?.close()
    document.removeEventListener('visibilitychange', this.onVisible)
  }

  private readonly onVisible = () => {
    if (document.visibilityState === 'visible' && !this.ws && !this.stopped) {
      if (this.timer) clearTimeout(this.timer)
      this.connect()
    }
  }

  private connect(): void {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.onState('connecting')
    let ws: WebSocket
    try {
      ws = new WebSocket(`${proto}//${location.host}/api/events`)
    } catch {
      this.scheduleReconnect()
      return
    }
    this.ws = ws
    let opened = 0
    ws.onopen = () => {
      opened = Date.now()
      this.onState('open')
    }
    ws.onmessage = (msg) => {
      if (typeof msg.data !== 'string') return
      try {
        this.onEvent(JSON.parse(msg.data) as UiEvent)
      } catch (e) {
        console.warn('Bad event frame', e)
      }
    }
    ws.onclose = () => {
      this.ws = null
      if (opened && Date.now() - opened > 10_000) this.attempt = 0
      this.onState('closed')
      if (!this.stopped) this.scheduleReconnect()
    }
  }

  private scheduleReconnect(): void {
    const base = Math.min(15_000, 500 * 2 ** this.attempt++)
    const delay = base / 2 + Math.random() * (base / 2)
    this.timer = setTimeout(() => this.connect(), delay)
  }
}
