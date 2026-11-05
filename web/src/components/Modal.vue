<script lang="ts">
const stack: symbol[] = []
</script>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from './Icon.vue'
import { useI18n } from '@/i18n'

withDefaults(defineProps<{ title: string; width?: string }>(), { width: '560px' })
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
const box = ref<HTMLElement | null>(null)

const id = Symbol('modal')

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && stack[stack.length - 1] === id) {
    e.stopPropagation()
    emit('close')
  }
}
onMounted(() => {
  stack.push(id)
  window.addEventListener('keydown', onKey, true)
  box.value?.focus()
})
onBeforeUnmount(() => {
  stack.splice(stack.indexOf(id), 1)
  window.removeEventListener('keydown', onKey, true)
})
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 p-6 pt-[8vh] backdrop-blur-[2px]" @mousedown.self="emit('close')">
      <div
        ref="box"
        tabindex="-1"
        role="dialog"
        :aria-label="title"
        class="flex max-h-[84vh] w-full flex-col rounded-xl border border-line bg-surface shadow-pop outline-none"
        :style="{ maxWidth: width }"
      >
        <header class="flex items-center gap-3 border-b border-line px-5 py-3.5">
          <h2 class="text-[15px] font-semibold">{{ title }}</h2>
          <slot name="header" />
          <button class="btn btn-ghost btn-icon ml-auto" :title="t('app.closeEsc')" @click="emit('close')"><Icon name="x" /></button>
        </header>
        <div class="scroll-thin min-h-0 flex-1 overflow-y-auto">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="flex items-center gap-2 border-t border-line px-5 py-3">
          <slot name="footer" />
        </footer>
      </div>
    </div>
  </Teleport>
</template>
