<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { MergePreview, Upload } from '@/generated/models'
import { api, errorMessage } from '@/api/client'
import { useAppStore } from '@/stores/app'
import { useDraftsStore } from '@/stores/drafts'
import { dateTime, plural } from '@/lib/format'
import Modal from './Modal.vue'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import { useI18n } from '@/i18n'

const { t, tn } = useI18n()
const props = defineProps<{ draftId: string }>()
const app = useAppStore()
const drafts = useDraftsStore()

const preview = ref<MergePreview | null>(null)
const uploads = ref<Upload[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const starting = ref(false)
const started = ref(false)
const expanded = ref<string | null>(null)

const draft = computed(() => drafts.drafts.find((d) => d.id === props.draftId) ?? (drafts.current?.draft.id === props.draftId ? drafts.current.draft : null))
const progress = computed(() => drafts.progress.get(props.draftId) ?? null)
const running = computed(() => (started.value || !!progress.value) && !progress.value?.finished)
const changeCount = computed(() => preview.value?.groups.reduce((n, g) => n + g.changeIds.length, 0) ?? 0)
const isProd = computed(() => (preview.value?.target ?? app.target) === 'prod')
const connected = computed(() => !!app.account?.connected)
const canUpload = computed(() => !!preview.value && changeCount.value > 0 && connected.value && !running.value)

const changesetBase = computed(() =>
  isProd.value ? 'https://www.openstreetmap.org/changeset/' : 'https://master.apis.dev.openstreetmap.org/changeset/',
)

async function load() {
  loading.value = true
  error.value = null
  try {
    const [p, u] = await Promise.all([api.mergePreview(props.draftId), api.uploads(props.draftId).catch(() => [] as Upload[])])
    preview.value = p
    uploads.value = u
    if (p.groups.length === 1) expanded.value = p.groups[0].key
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

watch(
  () => progress.value?.finished,
  async (done) => {
    if (done) {
      started.value = false
      uploads.value = await api.uploads(props.draftId).catch(() => uploads.value)
    }
  },
)

async function upload() {
  if (!canUpload.value) return
  starting.value = true
  drafts.progress.delete(props.draftId)
  try {
    await api.merge(props.draftId)
    started.value = true
  } catch (e) {
    app.fail(e, t('merge.error'))
  } finally {
    starting.value = false
  }
}

function conflictTitle(id: string): string {
  const c = drafts.current?.changes.find((x) => x.id === id)
  return c ? `${c.ref.type} ${c.ref.id}` : id
}

function goToConflict(id: string) {
  app.mergeDraftId = null
  void drafts.open(props.draftId, { review: true, changeId: id })
}

const pct = computed(() => (progress.value && progress.value.total ? Math.round((progress.value.done / progress.value.total) * 100) : 0))
</script>

<template>
  <Modal :title="t('merge.title', { title: draft?.title ?? t('merge.untitled') })" width="640px" @close="app.mergeDraftId = null">
    <template #header>
      <span
        class="chip font-bold uppercase"
        :class="isProd ? 'bg-prod-soft text-prod' : 'bg-surface-3 text-fg-2'"
      >
        {{ isProd ? t('merge.prod') : t('merge.dev') }}
      </span>
    </template>

    <div class="space-y-4 px-5 py-4">
      <div v-if="loading" class="flex items-center gap-2 text-fg-3"><Spinner /> {{ t('merge.preparing') }}</div>
      <div v-else-if="error" class="flex items-center gap-2 text-error"><Icon name="alert" />{{ error }}</div>

      <template v-else-if="preview">
        <div v-if="isProd" class="flex gap-2.5 rounded-md border border-line-strong border-l-4 border-l-prod bg-prod-soft p-3 text-[12px]">
          <Icon name="alert" class="mt-0.5 text-prod" />
          <div>
            <div class="font-bold text-prod">{{ t('merge.liveWarning') }}</div>
            <div class="text-fg-2">{{ t('merge.liveWarningBody') }}</div>
          </div>
        </div>

        <div v-if="!connected" class="flex items-center gap-3 rounded-md border border-line-strong bg-surface-2 p-3">
          <Icon name="user" class="text-fg-3" />
          <span class="flex-1">
            {{ app.account?.configured === false ? t('merge.oauthMissing') : t('merge.connectPrompt') }}
          </span>
          <a v-if="app.account?.configured !== false" href="/auth/login" class="btn btn-primary btn-sm">{{ t('merge.connect') }}</a>
        </div>

        <section v-if="preview.conflicts.length" class="space-y-1.5">
          <div class="flex items-center gap-2 text-[12px] font-bold text-changed">
            <Icon name="conflict" :size="14" /> {{ tn('merge.conflictsSkipped', preview.conflicts.length) }}
          </div>
          <p class="text-[12px] text-fg-2">{{ t('merge.conflictsHint') }}</p>
          <ul class="divide-y divide-line overflow-hidden rounded-md border border-line-strong">
            <li v-for="id in preview.conflicts" :key="id" class="flex items-center gap-2 px-3 py-1.5">
              <span class="font-mono text-[12px]">{{ conflictTitle(id) }}</span>
              <button class="btn btn-sm ml-auto" @click="goToConflict(id)">{{ t('merge.resolve') }}</button>
            </li>
          </ul>
        </section>

        <section class="space-y-1.5">
          <div class="flex items-baseline">
            <span class="section-title">{{ t('merge.changesets') }}</span>
            <span class="ml-auto text-[12px] text-fg-3">{{ plural(preview.groups.length, 'common.changesets') }} · {{ plural(changeCount, 'common.changes') }}</span>
          </div>
          <div v-if="!preview.groups.length" class="rounded-md border border-line-strong bg-surface-2 px-3 py-4 text-center text-fg-3">
            {{ t('merge.nothing') }}
          </div>
          <div v-for="g in preview.groups" :key="g.key" class="card overflow-hidden">
            <button class="flex w-full items-start gap-2 px-3 py-2 text-left transition-colors hover:bg-surface-3" @click="expanded = expanded === g.key ? null : g.key">
              <Icon :name="expanded === g.key ? 'chevron-down' : 'chevron-right'" :size="14" class="mt-0.5 text-fg-3" />
              <div class="min-w-0 flex-1">
                <div class="font-bold">{{ g.comment }}</div>
                <div class="text-[11.5px] text-fg-3">
                  {{ plural(g.changeIds.length, 'common.changes') }} · <span class="font-mono">{{ g.key }}</span>
                </div>
                <div class="mt-0.5 text-[11.5px]" :class="g.tags.source ? 'text-fg-2' : 'text-fg-3'">
                  source: <span :class="g.tags.source && 'font-mono'">{{ g.tags.source || t('merge.noSource') }}</span>
                </div>
              </div>
            </button>
            <div v-if="expanded === g.key" class="border-t border-line-strong bg-surface-2 px-3 py-2">
              <div class="section-title mb-1">{{ t('merge.changesetTags') }}</div>
              <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 font-mono text-[11.5px]">
                <template v-for="(v, k) in g.tags" :key="k">
                  <dt class="font-bold text-fg-2">{{ k }}</dt>
                  <dd class="break-words">{{ v }}</dd>
                </template>
              </dl>
            </div>
          </div>
        </section>

        <section v-if="progress || started" class="space-y-1.5">
          <div class="flex items-center gap-2 text-[12px]">
            <Spinner v-if="running" :size="12" />
            <Icon v-else-if="progress?.failed" name="alert" :size="14" class="text-error" />
            <Icon v-else name="check" :size="14" class="text-added" />
            <span class="font-bold">{{ progress?.message ?? t('merge.starting') }}</span>
            <span v-if="progress" class="ml-auto font-mono text-[11.5px] text-fg-3">{{ progress.done }}/{{ progress.total }}</span>
          </div>
          <div class="h-1.5 overflow-hidden rounded-sm bg-line">
            <div
              class="h-full transition-[width] duration-300"
              :class="progress?.failed ? 'bg-error' : progress?.finished ? 'bg-added' : 'bg-accent'"
              :style="{ width: `${pct}%` }"
            />
          </div>
          <div v-if="progress?.group" class="text-[11.5px] text-fg-3">
            {{ t('merge.group') }} <span class="font-mono">{{ progress.group }}</span>
            <template v-if="progress.changesetId">
              ·
              <a :href="changesetBase + progress.changesetId" target="_blank" rel="noopener" class="text-accent hover:underline">{{
                t('merge.changesetLink', { id: progress.changesetId })
              }}</a>
            </template>
          </div>
        </section>

        <section v-if="uploads.length" class="space-y-1.5">
          <div class="section-title">{{ t('merge.uploads') }}</div>
          <ul class="divide-y divide-line overflow-hidden rounded-md border border-line-strong text-[12px]">
            <li v-for="u in uploads" :key="u.id" class="flex items-center gap-2 px-3 py-1.5">
              <span class="chip" :class="u.error ? 'bg-removed-soft text-removed' : 'bg-added-soft text-added'">{{ u.status }}</span>
              <span class="min-w-0 flex-1 truncate" :title="u.error ?? u.comment">{{ u.error ?? u.comment }}</span>
              <a v-if="u.changesetId" :href="changesetBase + u.changesetId" target="_blank" rel="noopener" class="font-mono text-accent hover:underline">#{{ u.changesetId }}</a>
              <span class="text-fg-3">{{ dateTime(u.createdAt) }}</span>
            </li>
          </ul>
        </section>
      </template>
    </div>

    <template #footer>
      <span class="text-[12px] text-fg-3">
        <template v-if="app.account?.connected">{{ t('merge.as') }} <b class="text-fg-2">{{ app.account.displayName }}</b></template>
      </span>
      <button class="btn ml-auto" @click="app.mergeDraftId = null">{{ progress?.finished ? t('merge.done') : t('common.cancel') }}</button>
      <button class="btn" :class="isProd ? 'btn-danger' : 'btn-primary'" :disabled="!canUpload || starting" @click="upload">
        <Spinner v-if="starting || running" :size="12" /><Icon v-else name="upload" :size="13" />
        {{ tn(isProd ? 'merge.uploadToOsm' : 'merge.upload', changeCount) }}
      </button>
    </template>
  </Modal>
</template>
