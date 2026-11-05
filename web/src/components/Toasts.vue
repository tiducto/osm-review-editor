<script setup lang="ts">
import { useAppStore } from '@/stores/app'
import Icon from './Icon.vue'
import { useI18n } from '@/i18n'

const { t } = useI18n()
const app = useAppStore()
</script>

<template>
  <div class="pointer-events-none fixed bottom-4 left-1/2 z-[60] flex -translate-x-1/2 flex-col items-center gap-2">
    <TransitionGroup name="pop">
      <div
        v-for="toast in app.toasts"
        :key="toast.id"
        class="pointer-events-auto flex max-w-[520px] items-start gap-2.5 rounded-lg border border-line bg-surface px-3.5 py-2.5 text-[12.5px] shadow-pop"
        role="status"
        :data-testid="toast.testId"
      >
        <Icon
          :name="toast.kind === 'error' ? 'alert' : toast.kind === 'success' ? 'check' : 'info'"
          :class="toast.kind === 'error' ? 'text-error' : toast.kind === 'success' ? 'text-added' : 'text-accent'"
          class="mt-px"
        />
        <span class="min-w-0 break-words">{{ toast.text }}</span>
        <button
          v-if="toast.action"
          class="shrink-0 font-medium text-accent hover:underline"
          data-testid="toast-action"
          @click="(toast.action.run(), app.dismiss(toast.id))"
        >
          {{ toast.action.label }}
        </button>
        <button class="-mr-1 ml-1 text-fg-3 hover:text-fg" :title="t('common.dismiss')" @click="app.dismiss(toast.id)"><Icon name="x" :size="14" /></button>
      </div>
    </TransitionGroup>
  </div>
</template>
