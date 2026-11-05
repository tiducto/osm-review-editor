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
    <div class="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/50 p-6 pt-[8vh]" @mousedown.self="emit('close')">
      <div
        ref="box"
        tabindex="-1"
        role="dialog"
        :aria-label="title"
        class="flex max-h-[84vh] w-full flex-col overflow-hidden rounded-md bg-surface shadow-pop outline-none"
        :style="{ maxWidth: width }"
      >
        <header class="flex min-h-[48px] shrink-0 items-center gap-3 border-b border-line-strong bg-surface pl-5">
          <h2 class="text-[17px] leading-6 font-bold text-fg">{{ title }}</h2>
          <slot name="header" />
          <button class="ml-auto grid size-12 shrink-0 place-items-center self-start text-fg hover:bg-surface-3" :title="t('app.closeEsc')" @click="emit('close')"><Icon name="x" :size="16" /></button>
        </header>
        <div class="scroll-thin min-h-0 flex-1 overflow-y-auto">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="flex shrink-0 items-center gap-2 border-t border-line-strong bg-surface-2 px-5 py-3">
          <slot name="footer" />
        </footer>
      </div>
    </div>
  </Teleport>
</template>
