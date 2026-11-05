<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useMirrorStore } from '@/stores/mirror'
import { useAppStore } from '@/stores/app'
import { useI18n, type MessageKey } from '@/i18n'
import { dateTime, relativeTime } from '@/lib/format'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'

const { t, tn, formatNumber } = useI18n()
const mirror = useMirrorStore()
const app = useAppStore()
const open = ref(false)
const root = ref<HTMLElement | null>(null)

const dotClass = computed(() => {
  if (mirror.pendingReload) return 'bg-accent'
  return { ok: 'bg-added', behind: 'bg-warning', stale: 'bg-error', unknown: 'bg-fg-3' }[mirror.health]
})

const timeLabel = computed(() => {
  const d = mirror.dataTime
  if (!d) return t('shell.mirror.noData')
  const ts = Date.parse(d)
  const sameDay = new Date(ts).toDateString() === new Date().toDateString()
  return t('shell.mirror.asOf', { time: dateTime(d, sameDay ? { hour: '2-digit', minute: '2-digit' } : { day: 'numeric', month: 'numeric', hour: '2-digit', minute: '2-digit' }) })
})

function phaseLabel(s: string | null): string {
  const key = `shell.mirror.phase.${s}`
  return s && key in phaseKeys ? t(phaseKeys[key]) : (s ?? '')
}

const phaseKeys: Record<string, MessageKey> = {
  'shell.mirror.phase.clipping': 'shell.mirror.phase.clipping',
  'shell.mirror.phase.updating': 'shell.mirror.phase.updating',
  'shell.mirror.phase.checking': 'shell.mirror.phase.checking',
  'shell.mirror.phase.swapping': 'shell.mirror.phase.swapping',
  'shell.mirror.phase.rebuilding': 'shell.mirror.phase.rebuilding',
  'shell.mirror.phase.building': 'shell.mirror.phase.building',
}

function duration(seconds: number | null): string {
  if (seconds == null) return '—'
  if (seconds < 60) return t('shell.mirror.underMinute')
  if (seconds < 3600) return tn('shell.mirror.minutes', Math.round(seconds / 60))
  if (seconds < 86400) return tn('shell.mirror.hours', Math.round(seconds / 3600))
  return tn('shell.mirror.days', Math.round(seconds / 86400))
}

const healthLabel = computed(() => t(`shell.mirror.state.${mirror.health}` as MessageKey))
const title = computed(() => `${healthLabel.value} · ${t('shell.mirror.lag')}: ${duration(mirror.lagSeconds)}`)

const rows = computed(() => {
  const out: [string, string][] = []
  if (mirror.osmTime) out.push([t('shell.mirror.osmTime'), `${dateTime(mirror.osmTime)} (${relativeTime(mirror.osmTime)})`])
  if (mirror.fileTime && (mirror.isFileTime || mirror.fileTime !== mirror.osmTime)) out.push([t('shell.mirror.fileTime'), dateTime(mirror.fileTime)])
  if (mirror.sequence != null) out.push([t('shell.mirror.sequence'), formatNumber(mirror.sequence, { useGrouping: false })])
  out.push([t('shell.mirror.lag'), duration(mirror.lagSeconds)])
  if (mirror.lastRunAt) {
    const parts = [dateTime(mirror.lastRunAt)]
    if (mirror.lastDurationMs != null) parts.push(t('shell.mirror.seconds', { n: formatNumber(Math.round(mirror.lastDurationMs / 100) / 10) }))
    if (mirror.lastResult) parts.push(mirror.lastResult)
    out.push([t('shell.mirror.lastCycle'), parts.join(' · ')])
  }
  if (mirror.nextCycleAt) out.push([t('shell.mirror.nextCycle'), dateTime(mirror.nextCycleAt)])
  if (mirror.nextRebuildAt) out.push([t('shell.mirror.nextRebuild'), dateTime(mirror.nextRebuildAt)])
  if (app.mirror?.file) out.push([t('shell.mirror.file'), app.mirror.file])
  return out
})

function onDocClick(e: MouseEvent) {
  if (open.value && root.value && !root.value.contains(e.target as Node)) open.value = false
}
function onKey(e: KeyboardEvent) {
  if (open.value && e.key === 'Escape') open.value = false
}
onMounted(() => {
  document.addEventListener('mousedown', onDocClick)
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocClick)
  window.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div ref="root" class="relative" data-testid="mirror-indicator">
    <button
      class="bar-btn gap-1.5 px-2 font-normal whitespace-nowrap"
      :title="title"
      :aria-expanded="open"
      data-testid="mirror-pill"
      @click="open = !open"
    >
      <Spinner v-if="mirror.busy" :size="11" />
      <span v-else class="size-2 shrink-0 rounded-full" :class="dotClass" :data-health="mirror.health" />
      <span class="hidden md:inline">{{ timeLabel }}</span>
    </button>
    <div
      v-if="open"
      class="absolute top-full left-0 z-50 mt-1.5 w-[320px] max-w-[calc(100vw-16px)] rounded border border-line bg-surface p-3 text-[12px] text-fg shadow-pop"
      data-testid="mirror-popover"
    >
      <div class="mb-2 flex items-center gap-2">
        <span class="size-2 rounded-full" :class="dotClass" />
        <span class="font-bold">{{ t('shell.mirror.title') }}</span>
        <span class="text-fg-2">· {{ healthLabel }}</span>
      </div>
      <p v-if="mirror.busy" class="mb-2 flex items-center gap-1.5 text-fg-2"><Spinner :size="11" /> {{ t('shell.mirror.running', { state: phaseLabel(mirror.state) }) }}</p>
      <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
        <template v-for="[k, v] in rows" :key="k">
          <dt class="text-fg-3">{{ k }}</dt>
          <dd class="min-w-0 break-words">{{ v }}</dd>
        </template>
      </dl>
      <p v-if="mirror.lastError" class="mt-2 rounded bg-removed-soft px-2 py-1 text-removed" data-testid="mirror-error">
        <span class="font-bold">{{ t('shell.mirror.lastError') }}:</span> {{ mirror.lastError }}
      </p>
      <p v-if="mirror.pendingReload" class="mt-2 text-fg-2">{{ t('shell.mirror.reloadHint') }}</p>
      <div class="mt-3 flex flex-wrap gap-1.5">
        <button v-if="mirror.pendingReload" class="btn btn-primary btn-sm" data-testid="mirror-reload" @click="mirror.reloadMapData(); open = false">
          <Icon name="refresh" :size="12" /> {{ t('shell.mirror.reload') }}
        </button>
        <button
          v-if="mirror.updateSupported"
          class="btn btn-sm"
          :disabled="mirror.busy || mirror.requesting"
          :title="mirror.busy ? t('shell.mirror.updateBusy') : ''"
          data-testid="mirror-update-now"
          @click="mirror.updateNow()"
        >
          <Spinner v-if="mirror.requesting" :size="11" /> {{ t('shell.mirror.updateNow') }}
        </button>
        <span v-if="mirror.busy && mirror.updateSupported" class="self-center text-[11px] text-fg-3">{{ t('shell.mirror.updateBusy') }}</span>
      </div>
    </div>
  </div>
</template>
