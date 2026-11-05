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
const canUpload = computed(() => !!preview.value && !preview.value.conflicts.length && changeCount.value > 0 && connected.value && !running.value)

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
    app.fail(e, 'Merge')
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
  <Modal :title="`Merge “${draft?.title ?? 'draft'}”`" width="640px" @close="app.mergeDraftId = null">
    <template #header>
      <span
        class="chip font-semibold uppercase"
        :class="isProd ? 'bg-prod-soft text-prod ring-1 ring-prod/40' : 'bg-surface-3 text-fg-2'"
      >
        {{ isProd ? 'Production' : 'Dev API' }}
      </span>
    </template>

    <div class="space-y-4 px-5 py-4">
      <div v-if="loading" class="flex items-center gap-2 text-fg-3"><Spinner /> Preparing changesets…</div>
      <div v-else-if="error" class="flex items-center gap-2 text-error"><Icon name="alert" />{{ error }}</div>

      <template v-else-if="preview">
        <div v-if="isProd" class="flex gap-2.5 rounded-lg border border-prod/40 bg-prod-soft p-3 text-[12.5px]">
          <Icon name="alert" class="mt-0.5 text-prod" />
          <div>
            <div class="font-semibold text-prod">This uploads to the live OpenStreetMap database.</div>
            <div class="text-fg-2">Every approved change becomes public and visible to everyone.</div>
          </div>
        </div>

        <div v-if="!connected" class="flex items-center gap-3 rounded-lg border border-line bg-surface-2 p-3">
          <Icon name="user" class="text-fg-3" />
          <span class="flex-1">
            {{ app.account?.configured === false ? 'OAuth is not configured on the server for this target.' : 'Connect your OSM account to upload.' }}
          </span>
          <a v-if="app.account?.configured !== false" href="/auth/login" class="btn btn-primary btn-sm">Connect</a>
        </div>

        <section v-if="preview.conflicts.length" class="space-y-1.5">
          <div class="flex items-center gap-2 text-[12.5px] font-semibold text-changed">
            <Icon name="conflict" :size="14" /> {{ plural(preview.conflicts.length, 'conflict') }} to resolve first
          </div>
          <ul class="divide-y divide-line overflow-hidden rounded-md border border-line">
            <li v-for="id in preview.conflicts" :key="id" class="flex items-center gap-2 px-3 py-1.5">
              <span class="font-mono text-[12px]">{{ conflictTitle(id) }}</span>
              <button class="btn btn-sm ml-auto" @click="goToConflict(id)">Resolve</button>
            </li>
          </ul>
        </section>

        <section class="space-y-1.5">
          <div class="flex items-baseline">
            <span class="section-title">Changesets</span>
            <span class="ml-auto text-[12px] text-fg-3">{{ plural(preview.groups.length, 'changeset') }} · {{ plural(changeCount, 'change') }}</span>
          </div>
          <div v-if="!preview.groups.length" class="rounded-md border border-dashed border-line px-3 py-4 text-center text-fg-3">
            Nothing approved to upload yet.
          </div>
          <div v-for="g in preview.groups" :key="g.key" class="overflow-hidden rounded-md border border-line">
            <button class="flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-surface-2" @click="expanded = expanded === g.key ? null : g.key">
              <Icon :name="expanded === g.key ? 'chevron-down' : 'chevron-right'" :size="14" class="mt-0.5 text-fg-3" />
              <div class="min-w-0 flex-1">
                <div class="font-medium">{{ g.comment }}</div>
                <div class="text-[11.5px] text-fg-3">{{ plural(g.changeIds.length, 'change') }} · <span class="font-mono">{{ g.key }}</span></div>
              </div>
            </button>
            <div v-if="expanded === g.key" class="border-t border-line bg-surface-2 px-3 py-2">
              <div class="section-title mb-1">Changeset tags</div>
              <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 font-mono text-[11.5px]">
                <template v-for="(v, k) in g.tags" :key="k">
                  <dt class="text-fg-3">{{ k }}</dt>
                  <dd class="break-words">{{ v }}</dd>
                </template>
              </dl>
            </div>
          </div>
        </section>

        <section v-if="progress || started" class="space-y-1.5">
          <div class="flex items-center gap-2 text-[12.5px]">
            <Spinner v-if="running" :size="12" />
            <Icon v-else-if="progress?.failed" name="alert" :size="14" class="text-error" />
            <Icon v-else name="check" :size="14" class="text-added" />
            <span class="font-medium">{{ progress?.message ?? 'Starting upload…' }}</span>
            <span v-if="progress" class="ml-auto font-mono text-[11.5px] text-fg-3">{{ progress.done }}/{{ progress.total }}</span>
          </div>
          <div class="h-1.5 overflow-hidden rounded-full bg-surface-3">
            <div
              class="h-full transition-[width] duration-300"
              :class="progress?.failed ? 'bg-error' : progress?.finished ? 'bg-added' : 'bg-accent'"
              :style="{ width: `${pct}%` }"
            />
          </div>
          <div v-if="progress?.group" class="text-[11.5px] text-fg-3">
            Group <span class="font-mono">{{ progress.group }}</span>
            <template v-if="progress.changesetId">
              · <a :href="changesetBase + progress.changesetId" target="_blank" rel="noopener" class="text-accent hover:underline">changeset {{ progress.changesetId }}</a>
            </template>
          </div>
        </section>

        <section v-if="uploads.length" class="space-y-1.5">
          <div class="section-title">Uploads</div>
          <ul class="divide-y divide-line overflow-hidden rounded-md border border-line text-[12px]">
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
        <template v-if="app.account?.connected">As <b class="text-fg-2">{{ app.account.displayName }}</b></template>
      </span>
      <button class="btn ml-auto" @click="app.mergeDraftId = null">{{ progress?.finished ? 'Done' : 'Cancel' }}</button>
      <button class="btn" :class="isProd ? 'btn-danger' : 'btn-primary'" :disabled="!canUpload || starting" @click="upload">
        <Spinner v-if="starting || running" :size="12" /><Icon v-else name="upload" :size="13" />
        Upload {{ plural(changeCount, 'change') }}{{ isProd ? ' to OSM' : '' }}
      </button>
    </template>
  </Modal>
</template>
