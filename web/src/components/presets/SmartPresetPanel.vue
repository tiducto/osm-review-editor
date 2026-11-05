<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useEditPresetsStore } from '@/stores/presetEdit'
import { usePresetsStore } from '@/stores/presets'
import Icon from '../Icon.vue'

const store = useEditPresetsStore()
const presets = usePresetsStore()
const t = presets.t
const params = reactive<Record<string, string>>({})

watch(
  () => store.smartRun?.preset.id,
  () => {
    for (const k of Object.keys(params)) delete params[k]
    Object.assign(params, store.smartRun?.params ?? {})
  },
  { immediate: true },
)

function label(k: string) {
  return store.smartRun?.preset.params[k]?.label ?? k
}
</script>

<template>
  <section v-if="store.smartRun" class="space-y-2.5 border-b border-claude/40 bg-claude-soft/40 px-4 py-3" data-testid="smart-panel">
    <div class="flex items-center gap-2">
      <Icon name="sparkle" :size="14" class="text-claude" />
      <div class="min-w-0 flex-1">
        <div class="text-[10.5px] font-semibold tracking-wide text-fg-3 uppercase">{{ t('smart.title') }}</div>
        <div class="truncate font-semibold">{{ store.smartRun.preset.name }}</div>
      </div>
      <span class="text-[11px] text-fg-3">{{ t('smart.steps', { n: store.smartRun.preset.steps.length }) }}</span>
    </div>
    <p v-if="store.smartRun.preset.description" class="text-[12px] text-fg-2">{{ store.smartRun.preset.description }}</p>
    <div v-if="Object.keys(store.smartRun.preset.params).length" class="grid grid-cols-2 gap-2">
      <label v-for="(p, k) in store.smartRun.preset.params" :key="k" class="flex min-w-0 flex-col gap-1">
        <span class="text-[11.5px] font-medium text-fg-2">{{ label(String(k)) }}</span>
        <select v-if="p.type === 'combo'" v-model="params[k]" class="input" :data-param="k">
          <option v-for="o in p.options" :key="o" :value="o">{{ o }}</option>
        </select>
        <select v-else-if="p.type === 'check'" v-model="params[k]" class="input" :data-param="k">
          <option value="yes">yes</option>
          <option value="no">no</option>
        </select>
        <input v-else v-model="params[k]" class="input" :inputmode="p.type === 'number' ? 'decimal' : 'text'" :data-param="k" />
      </label>
    </div>
    <div v-if="store.smartRun.error" class="rounded-md border border-removed/40 bg-removed-soft px-2.5 py-2 text-[12px] text-removed" data-testid="smart-error">
      <div class="font-semibold">{{ t('smart.failed') }}</div>
      {{ store.smartRun.error }}
    </div>
    <div v-if="store.smartRun.preview" class="space-y-1 text-[12px]" data-testid="smart-preview">
      <p class="text-fg-2">{{ t('smart.previewHint') }}</p>
      <ul class="list-disc space-y-0.5 pl-4 font-mono text-[11px] text-fg-2">
        <li v-for="(s, i) in store.smartRun.summary" :key="i">{{ s }}</li>
      </ul>
    </div>
    <div class="flex items-center gap-1.5">
      <button class="btn btn-sm" data-testid="smart-preview-btn" @click="store.previewSmart({ ...params })">
        {{ store.smartRun.preview ? t('smart.rerun') : t('smart.run') }}
      </button>
      <button class="btn btn-sm btn-primary" :disabled="!store.smartRun.preview" data-testid="smart-confirm" @click="store.confirmSmart()">{{ t('smart.confirm') }}</button>
      <button class="btn btn-sm btn-ghost ml-auto" data-testid="smart-cancel" @click="store.cancelSmart()">{{ t('smart.cancel') }}</button>
    </div>
  </section>
</template>
