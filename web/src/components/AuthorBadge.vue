<script setup lang="ts">
import { computed } from 'vue'
import Icon from './Icon.vue'
import { parseAuthor } from '@/lib/authors'
import { useRulesStore } from '@/stores/rules'

const props = defineProps<{ author: string }>()
const rules = useRulesStore()
const info = computed(() => parseAuthor(props.author))
const title = computed(() =>
  info.value.kind === 'rule' ? (rules.ruleById.get(info.value.ruleId ?? '')?.title ?? info.value.label) : info.value.label,
)
</script>

<template>
  <span
    v-if="info.kind === 'claude'"
    class="chip bg-claude-soft text-claude ring-1 ring-claude/30"
    title="Proposed by Claude over MCP"
  >
    <Icon name="sparkle" :size="12" :stroke="2" />Claude
  </span>
  <span v-else-if="info.kind === 'rule'" class="chip max-w-48 bg-surface-3 text-fg-2" :title="`Rule: ${title}`">
    <Icon name="rule" :size="12" /><span class="truncate">{{ title }}</span>
  </span>
  <span v-else-if="info.kind === 'remote'" class="chip bg-surface-3 text-fg-2" title="From an external QA tool (remote control)">
    <Icon name="remote" :size="12" />Remote
  </span>
  <span v-else class="chip bg-accent-soft text-accent" :title="author">
    <Icon name="user" :size="12" />{{ info.label }}
  </span>
</template>
