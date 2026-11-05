<script setup lang="ts">
import { useI18n } from '@/i18n'

withDefaults(defineProps<{ size?: number }>(), { size: 14 })
const { t } = useI18n()
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" class="shrink-0 animate-spin" :aria-label="t('app.loading')">
    <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-opacity="0.2" stroke-width="3" />
    <path d="M21 12a9 9 0 0 0-9-9" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" />
  </svg>
</template>
