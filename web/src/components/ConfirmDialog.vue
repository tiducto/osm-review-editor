<script setup lang="ts">
import Modal from './Modal.vue'
import { useI18n } from '@/i18n'

withDefaults(defineProps<{ title: string; confirmLabel?: string; danger?: boolean }>(), { danger: false })
const { t } = useI18n()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
</script>

<template>
  <Modal :title="title" width="440px" @close="emit('cancel')">
    <div class="px-5 py-4 text-[13px] text-fg-2"><slot /></div>
    <template #footer>
      <button class="btn ml-auto" @click="emit('cancel')">{{ t('common.cancel') }}</button>
      <button class="btn" :class="danger ? 'btn-danger' : 'btn-primary'" @click="emit('confirm')">{{ confirmLabel ?? t('common.confirm') }}</button>
    </template>
  </Modal>
</template>
