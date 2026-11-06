<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMapStore } from '@/stores/map'
import { FEATURE_GROUPS, type FeatureGroup } from '@/map/osm/features'
import Icon from './Icon.vue'
import { useI18n } from '@/i18n'

const mapStore = useMapStore()
const { t } = useI18n()
const OPEN_KEY = 'ore.featuresOpen'

function readOpen(): boolean {
  try {
    return localStorage.getItem(OPEN_KEY) === '1'
  } catch {
    return false
  }
}

const open = ref(readOpen())
watch(open, (v) => {
  try {
    localStorage.setItem(OPEN_KEY, v ? '1' : '0')
  } catch {}
})

const hidden = computed(() => new Set(mapStore.hiddenFeatures))

function toggle(id: FeatureGroup) {
  mapStore.setFeatureVisible(id, hidden.value.has(id))
}
</script>

<template>
  <section class="border-b border-line px-3 py-3" data-testid="map-features">
    <button class="flex w-full items-center gap-1.5" :aria-expanded="open" data-testid="map-features-toggle" @click="open = !open">
      <Icon :name="open ? 'chevron-down' : 'chevron-right'" :size="12" class="text-fg-3" />
      <span class="section-title">{{ t('shell.features.title') }}</span>
      <span v-if="hidden.size" class="ml-auto text-[11.5px] text-fg-3">{{ t('shell.features.hidden', { n: hidden.size }) }}</span>
    </button>
    <div v-if="open" class="mt-2 space-y-0.5">
      <label
        v-for="g in FEATURE_GROUPS"
        :key="g.id"
        class="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-[12.5px] hover:bg-surface-2"
        :data-testid="`feature-${g.id}`"
      >
        <input type="checkbox" class="accent-accent" :checked="!hidden.has(g.id)" @change="toggle(g.id)" />
        <span :class="hidden.has(g.id) ? 'text-fg-3' : 'text-fg'">{{ t(`shell.features.group.${g.id}`) }}</span>
      </label>
      <div class="flex gap-3 pt-1 text-[11.5px]">
        <button class="text-accent hover:underline" data-testid="features-all" @click="mapStore.setHiddenFeatures([])">{{ t('shell.features.enableAll') }}</button>
        <button
          class="text-fg-3 hover:text-accent"
          data-testid="features-none"
          @click="mapStore.setHiddenFeatures(FEATURE_GROUPS.map((g) => g.id))"
        >
          {{ t('shell.features.disableAll') }}
        </button>
      </div>
    </div>
  </section>
</template>
