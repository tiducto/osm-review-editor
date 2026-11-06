<script setup lang="ts">
defineProps<{ values: string[] | null | undefined }>()
</script>

<template>
  <p v-if="values && values.length" class="mt-0.5 truncate text-[10.5px] text-fg-3" :title="values.join('\n')">
    {{ values.join(' · ') }}
  </p>
</template>
