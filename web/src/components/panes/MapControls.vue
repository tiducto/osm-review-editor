<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePanelStore, type PaneName } from '@/stores/panel'
import { useLayoutStore } from '@/stores/layout'
import { useMapStore } from '@/stores/map'
import { useAppStore } from '@/stores/app'
import { useHighlightsStore } from '@/stores/highlights'
import { useScansStore } from '@/stores/scans'
import Icon, { type IconName } from '../Icon.vue'
import Spinner from '../Spinner.vue'
import { useI18n } from '@/i18n'

const { t } = useI18n()
const panel = usePanelStore()
const layout = useLayoutStore()
const mapStore = useMapStore()
const app = useAppStore()
const hl = useHighlightsStore()
const scans = useScansStore()

interface PaneButton {
  name: PaneName
  icon: IconName
  key: string | null
}

const groups: PaneButton[][] = [
  [
    { name: 'background', icon: 'layers', key: 'B' },
    { name: 'map-data', icon: 'database', key: 'F' },
    { name: 'validation', icon: 'alert', key: 'I' },
  ],
  [
    { name: 'rules', icon: 'wrench', key: 'L' },
    { name: 'presets', icon: 'star', key: 'P' },
    { name: 'drafts', icon: 'list', key: 'D' },
  ],
  [{ name: 'settings', icon: 'settings', key: null }],
]

const canLocate = typeof navigator !== 'undefined' && 'geolocation' in navigator
const locating = ref(false)

function badge(name: PaneName): number | null {
  if (name === 'map-data') return hl.active.length || null
  if (name === 'validation') return scans.report?.scan.issueCount ?? null
  return null
}

function zoom(delta: number) {
  const map = mapStore.instance()
  if (!map) return
  if (delta > 0) map.zoomIn()
  else map.zoomOut()
}

function locate() {
  if (!canLocate || locating.value) return
  locating.value = true
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      locating.value = false
      mapStore.flyTo({ lng: pos.coords.longitude, lat: pos.coords.latitude }, Math.max(mapStore.zoom, 17))
    },
    (err) => {
      locating.value = false
      app.toast(t('shell.controls.geolocateFailed', { error: err.message }), 'error')
    },
    { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 },
  )
}

function title(b: PaneButton): string {
  const label = t(`shell.pane.${b.name}`)
  return b.key ? t('shell.pane.withKey', { label, key: b.key }) : label
}

const size = computed(() => (layout.phone ? 'size-9' : 'size-10'))
</script>

<template>
  <nav class="map-controls pointer-events-none flex flex-col" :class="layout.phone ? 'gap-1.5' : 'gap-2.5'" :aria-label="t('shell.controls.label')" data-testid="map-controls">
    <div class="flex flex-col">
      <button :class="size" class="map-control" :title="t('shell.controls.zoomIn')" data-testid="zoom-in" @click="zoom(1)"><Icon name="plus" :size="18" /></button>
      <button :class="size" class="map-control" :title="t('shell.controls.zoomOut')" data-testid="zoom-out" @click="zoom(-1)"><Icon name="minus" :size="18" /></button>
    </div>
    <div v-if="canLocate" class="flex flex-col">
      <button :class="size" class="map-control" :title="t('shell.controls.geolocate')" :aria-busy="locating" data-testid="geolocate" @click="locate">
        <Spinner v-if="locating" :size="16" />
        <Icon v-else name="geolocate" :size="18" />
      </button>
    </div>
    <div v-for="(group, gi) in groups" :key="gi" class="flex flex-col">
      <button
        v-for="b in group"
        :key="b.name"
        :class="size"
        class="map-control relative"
        :title="title(b)"
        :aria-pressed="panel.pane === b.name"
        :data-testid="`pane-button-${b.name}`"
        @click="panel.toggle(b.name)"
      >
        <Icon :name="b.icon" :size="18" />
        <span
          v-if="badge(b.name)"
          class="absolute top-0.5 right-0.5 min-w-3.5 rounded-sm bg-accent px-0.5 text-center text-[9.5px] leading-3.5 font-bold text-white"
        >{{ badge(b.name) }}</span>
      </button>
    </div>
  </nav>
</template>
