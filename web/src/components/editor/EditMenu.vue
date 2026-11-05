<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useEditorStore } from '@/stores/editor'

const editor = useEditorStore()
const box = ref<HTMLElement | null>(null)

const style = computed(() => {
  const m = editor.menu
  const parent = box.value?.parentElement
  if (!m) return {}
  const w = parent?.clientWidth ?? 9999
  const h = parent?.clientHeight ?? 9999
  const flipX = m.x + 240 > w
  const flipY = m.y + 40 + editor.operations.length * 30 > h
  return {
    left: `${flipX ? m.x - 4 : m.x + 4}px`,
    top: `${flipY ? m.y - 4 : m.y + 4}px`,
    transform: `translate(${flipX ? '-100%' : '0'}, ${flipY ? '-100%' : '0'})`,
  }
})

function onDoc(e: MouseEvent) {
  if (box.value && !box.value.contains(e.target as Node)) editor.menu = null
}
onMounted(() => document.addEventListener('mousedown', onDoc, true))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDoc, true))
</script>

<template>
  <div
    ref="box"
    class="absolute z-30 w-56 overflow-hidden rounded-lg border border-line bg-surface py-1 text-[12.5px] shadow-pop"
    :style="style"
    role="menu"
    data-testid="edit-menu"
    @contextmenu.prevent
  >
    <div v-if="!editor.operations.length" class="px-3 py-1.5 text-fg-3">No operations for this selection</div>
    <button
      v-for="op in editor.operations"
      :key="op.id"
      role="menuitem"
      class="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-surface-3"
      :class="op.disabled ? 'cursor-not-allowed text-fg-3' : 'text-fg'"
      :aria-disabled="!!op.disabled"
      :title="op.tooltip"
      :data-op="op.id"
      @click="op.disabled ? null : editor.runOperation(op.id)"
    >
      <span class="flex-1 truncate">{{ op.title }}</span>
      <span v-if="op.keys[0]" class="kbd">{{ op.keys[0] }}</span>
    </button>
  </div>
</template>
