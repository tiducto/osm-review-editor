<script setup lang="ts">
import { computed } from 'vue'
import Modal from './Modal.vue'
import { useRemoteStore } from '@/stores/remote'
import { refString } from '@/lib/osm'
import { useI18n, type MessageKey } from '@/i18n'

const remote = useRemoteStore()
const { t, tn } = useI18n()

const r = computed(() => remote.current)
const KNOWN = ['import', 'load_data', 'add_node', 'addtags']
const title = computed(() => {
  const cmd = r.value?.command ?? ''
  return t((KNOWN.includes(cmd) ? `remote.title.${cmd}` : 'remote.title.other') as MessageKey)
})
const loads = computed(() => r.value?.command === 'import' || r.value?.command === 'load_data')
const tags = computed(() => Object.entries(r.value?.tags ?? {}))
const objects = computed(() => (r.value?.objects ?? []).map(refString))
</script>

<template>
  <Modal v-if="r" :key="r.id" :title="title" width="480px" @close="remote.decide(false)">
    <div class="space-y-3 px-5 py-4 text-[12px] text-fg" data-testid="remote-confirm" :data-command="r.command">
      <p class="flex items-start gap-2 rounded-md border border-changed/30 bg-changed-soft px-3 py-2 text-changed">{{ t('remote.warning') }}</p>
      <dl class="grid grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-1.5">
        <template v-if="r.url">
          <dt class="text-fg-3">{{ t('remote.url') }}</dt>
          <dd class="font-mono text-[11.5px] break-all" data-testid="remote-url">{{ r.url }}</dd>
        </template>
        <template v-if="r.host">
          <dt class="text-fg-3">{{ t('remote.host') }}</dt>
          <dd class="font-mono text-[12.5px] font-semibold break-all" data-testid="remote-host">{{ r.host }}</dd>
        </template>
        <template v-if="r.title">
          <dt class="text-fg-3">{{ t('remote.layer') }}</dt>
          <dd class="break-words">{{ r.title }}</dd>
        </template>
        <template v-if="r.objectCount !== null">
          <dt class="text-fg-3">{{ t('remote.objectCount') }}</dt>
          <dd>{{ r.objectCount }}</dd>
        </template>
        <template v-if="objects.length">
          <dt class="text-fg-3">{{ t('remote.objects') }}</dt>
          <dd class="font-mono text-[11.5px] break-all" data-testid="remote-objects">{{ objects.join(' ') }}</dd>
        </template>
        <template v-if="r.location">
          <dt class="text-fg-3">{{ t('remote.location') }}</dt>
          <dd class="font-mono text-[11.5px]">{{ r.location.lat.toFixed(6) }}, {{ r.location.lng.toFixed(6) }}</dd>
        </template>
        <template v-if="tags.length">
          <dt class="text-fg-3">{{ t('remote.tags') }}</dt>
          <dd class="font-mono text-[11.5px] break-all" data-testid="remote-tags">
            <div v-for="[k, v] in tags" :key="k">{{ k }}={{ v }}</div>
          </dd>
        </template>
        <template v-if="r.changesetComment">
          <dt class="text-fg-3">{{ t('remote.comment') }}</dt>
          <dd class="break-words">{{ r.changesetComment }}</dd>
        </template>
        <template v-if="r.changesetSource">
          <dt class="text-fg-3">{{ t('remote.source') }}</dt>
          <dd class="break-words">{{ r.changesetSource }}</dd>
        </template>
      </dl>
      <p class="text-fg-3">{{ t('remote.draftHint') }}</p>
      <p v-if="remote.pending.length > 1" class="text-fg-3">{{ tn('remote.queue', remote.pending.length - 1) }}</p>
    </div>
    <template #footer>
      <button class="btn ml-auto" data-testid="remote-cancel" :disabled="remote.busy" @click="remote.decide(false)">{{ t('common.cancel') }}</button>
      <button class="btn btn-primary" data-testid="remote-load" :disabled="remote.busy" @click="remote.decide(true)">
        {{ loads ? t('remote.load') : t('remote.createDraft') }}
      </button>
    </template>
  </Modal>
</template>
