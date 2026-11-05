<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { useMapStore } from '@/stores/map'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'

const app = useAppStore()
const mapStore = useMapStore()
const open = ref(false)
const filter = ref('')
const filterInput = ref<HTMLInputElement | null>(null)

watch(open, async (v) => {
  if (!v) return
  await nextTick()
  filterInput.value?.focus()
})

const list = computed(() => {
  const f = filter.value.trim().toLowerCase()
  return f ? app.areas.filter((a) => a.name.toLowerCase().includes(f)) : app.areas
})

function choose(name: string | null) {
  open.value = false
  filter.value = ''
  void mapStore.setArea(name)
}
</script>

<template>
  <div class="relative">
    <button class="btn h-8 max-w-32 gap-2 sm:max-w-56" :class="open && 'bg-surface-3'" @click="open = !open">
      <Spinner v-if="mapStore.areaLoading" :size="12" />
      <Icon v-else name="pin" :size="14" class="text-fg-3" />
      <span class="truncate">{{ mapStore.area?.name ?? 'Map view' }}</span>
      <Icon name="chevron-down" :size="13" class="text-fg-3" />
    </button>
    <Transition name="pop">
      <div v-if="open" class="absolute top-full left-0 z-40 mt-1.5 w-64 overflow-hidden rounded-lg border border-line bg-surface shadow-pop">
        <div class="border-b border-line p-1.5">
          <input ref="filterInput" v-model="filter" class="input" placeholder="Filter areas…" @keydown.esc="open = false" />
        </div>
        <ul class="scroll-thin max-h-72 overflow-y-auto py-1">
          <li>
            <button class="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-surface-2" @click="choose(null)">
              <Icon name="target" :size="14" class="text-fg-3" />
              <span class="flex-1">Map view</span>
              <Icon v-if="!mapStore.area" name="check" :size="14" class="text-accent" />
            </button>
            <p class="px-3 pb-1.5 pl-9 text-[11px] text-fg-3">Rules follow the visible map</p>
          </li>
          <li v-for="a in list" :key="a.name">
            <button class="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-surface-2" @click="choose(a.name)">
              <Icon name="pin" :size="14" class="text-fg-3" />
              <span class="flex-1 truncate">{{ a.name }}</span>
              <Icon v-if="mapStore.area?.name === a.name" name="check" :size="14" class="text-accent" />
            </button>
          </li>
          <li v-if="!app.areas.length" class="px-3 py-2 text-[12px] text-fg-3">No area presets. Search for a place name to use its boundary.</li>
        </ul>
      </div>
    </Transition>
    <div v-if="open" class="fixed inset-0 z-30" @click="open = false" />
  </div>
</template>
