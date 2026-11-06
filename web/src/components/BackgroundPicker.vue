<script setup lang="ts">
import { useMapStore } from '@/stores/map'
import { useI18n } from '@/i18n'

defineProps<{ details?: boolean; name?: string }>()

const mapStore = useMapStore()
const { t } = useI18n()
</script>

<template>
  <div class="space-y-2" data-testid="background-picker">
    <div class="overflow-hidden rounded-md border border-line" role="radiogroup" :aria-label="t('imagery.background')" data-testid="imagery-list">
      <label
        v-for="s in mapStore.backgrounds"
        :key="s.id"
        class="flex cursor-pointer items-center gap-2.5 border-b border-line px-3 py-1.5 hover:bg-surface-2"
        :data-testid="`background-${s.id}`"
      >
        <input type="radio" :name="name ?? 'background'" :checked="mapStore.imageryId === s.id" @change="mapStore.setImagery(s.id)" />
        <span class="min-w-0 flex-1 truncate">{{ s.name }}</span>
        <template v-if="details">
          <span class="chip bg-surface-3 font-mono text-fg-3 uppercase">{{ s.type }}</span>
          <span class="text-[11px] text-fg-3">z{{ s.maxZoom }}</span>
        </template>
      </label>
      <label class="flex cursor-pointer items-center gap-2.5 px-3 py-1.5 hover:bg-surface-2" data-testid="background-none">
        <input type="radio" :name="name ?? 'background'" :checked="!mapStore.imageryId" @change="mapStore.setImagery(null)" />
        <span class="text-fg-2">{{ t('common.none') }}</span>
      </label>
    </div>
    <template v-if="mapStore.overlays.length">
      <div class="pt-1 text-[11.5px] font-medium text-fg-2">{{ t('imagery.overlays') }}</div>
      <div class="overflow-hidden rounded-md border border-line">
        <label
          v-for="s in mapStore.overlays"
          :key="s.id"
          class="flex cursor-pointer items-center gap-2.5 border-b border-line px-3 py-1.5 last:border-b-0 hover:bg-surface-2"
          :data-testid="`overlay-${s.id}`"
        >
          <input type="checkbox" class="accent-accent" :checked="mapStore.overlayIds.includes(s.id)" @change="mapStore.setOverlay(s.id, ($event.target as HTMLInputElement).checked)" />
          <span class="min-w-0 flex-1 truncate">{{ s.name }}</span>
          <template v-if="details">
            <span class="chip bg-surface-3 font-mono text-fg-3 uppercase">{{ s.type }}</span>
            <span class="text-[11px] text-fg-3">z{{ s.maxZoom }}</span>
          </template>
        </label>
      </div>
    </template>
  </div>
</template>
