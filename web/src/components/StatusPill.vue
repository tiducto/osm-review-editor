<script setup lang="ts">
import { computed } from 'vue'
import type { ChangeStatus, DraftStatus } from '@/generated/models'
import { t, type MessageKey } from '@/i18n'

const props = defineProps<{ status: ChangeStatus | DraftStatus }>()

const look: Record<ChangeStatus | DraftStatus, { cls: string; label: MessageKey }> = {
  pending: { cls: 'bg-surface-3 text-fg-2', label: 'status.pending' },
  approved: { cls: 'bg-added-soft text-added', label: 'status.approved' },
  rejected: { cls: 'bg-removed-soft text-removed', label: 'status.rejected' },
  conflict: { cls: 'bg-changed-soft text-changed', label: 'status.conflict' },
  uploaded: { cls: 'bg-accent-soft text-accent', label: 'status.uploaded' },
  open: { cls: 'bg-accent-soft text-accent', label: 'status.open' },
  merged: { cls: 'bg-added-soft text-added', label: 'status.merged' },
  partially_merged: { cls: 'bg-changed-soft text-changed', label: 'status.partiallyMerged' },
  closed: { cls: 'bg-surface-3 text-fg-3', label: 'status.closed' },
}
const l = computed(() => look[props.status])
</script>

<template>
  <span class="chip" :class="l.cls">{{ t(l.label) }}</span>
</template>
