<script setup lang="ts">
import { ref, watch } from 'vue'
import { iconMarkup } from '@/map/osm/icons'
import Icon from '../Icon.vue'

const props = withDefaults(defineProps<{ icon: string | null | undefined; size?: number; smart?: boolean }>(), { size: 36, smart: false })
const svg = ref<string | null>(null)

watch(
  () => props.icon,
  async (icon) => {
    svg.value = null
    if (!icon) return
    const s = await iconMarkup(icon)
    if (props.icon === icon) svg.value = s
  },
  { immediate: true },
)
</script>

<template>
  <span
    class="grid shrink-0 place-items-center rounded-md border bg-surface"
    :class="smart ? 'border-claude/50 text-claude' : 'border-line-strong text-fg'"
    :style="{ width: `${size}px`, height: `${size}px`, padding: `${Math.round(size / 6)}px` }"
  >
    <span v-if="svg" class="block size-full" v-html="svg" />
    <Icon v-else :name="smart ? 'sparkle' : 'dot'" :size="Math.round(size / 2.2)" />
  </span>
</template>
