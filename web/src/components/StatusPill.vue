<script setup lang="ts">
import { computed } from 'vue'
import type { ChangeStatus, DraftStatus } from '@/generated/models'

const props = defineProps<{ status: ChangeStatus | DraftStatus }>()

const look: Record<ChangeStatus | DraftStatus, { cls: string; label: string }> = {
  pending: { cls: 'bg-surface-3 text-fg-2', label: 'Pending' },
  approved: { cls: 'bg-added-soft text-added', label: 'Approved' },
  rejected: { cls: 'bg-removed-soft text-removed', label: 'Rejected' },
  conflict: { cls: 'bg-changed-soft text-changed', label: 'Conflict' },
  uploaded: { cls: 'bg-accent-soft text-accent', label: 'Uploaded' },
  open: { cls: 'bg-accent-soft text-accent', label: 'Open' },
  merged: { cls: 'bg-added-soft text-added', label: 'Merged' },
  partially_merged: { cls: 'bg-changed-soft text-changed', label: 'Partly merged' },
  closed: { cls: 'bg-surface-3 text-fg-3', label: 'Closed' },
}
const l = computed(() => look[props.status])
</script>

<template>
  <span class="chip" :class="l.cls">{{ l.label }}</span>
</template>
