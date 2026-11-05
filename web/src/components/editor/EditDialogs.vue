<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useEditorStore } from '@/stores/editor'
import { useMapStore } from '@/stores/map'
import { useEditIssuesStore } from '@/stores/editIssues'
import { refString } from '@/lib/osm'
import { useI18n } from '@/i18n'
import Modal from '../Modal.vue'
import ConfirmDialog from '../ConfirmDialog.vue'
import Spinner from '../Spinner.vue'

const editor = useEditorStore()
const mapStore = useMapStore()
const issues = useEditIssuesStore()
const { t, tn } = useI18n()

const title = ref('')
const comment = ref('')

watch(
  () => editor.saveDialog,
  (open) => {
    if (!open) return
    const where = mapStore.area?.name ?? mapStore.scopeName
    const date = new Date().toISOString().slice(0, 10)
    title.value ||= where ? t('dialog.save.defaultTitleIn', { where, date }) : t('dialog.save.defaultTitle', { date })
  },
)

const takeOverDrafts = computed(() => {
  const list = editor.takeOver?.openChanges ?? []
  const by = new Map<string, { title: string; refs: string[] }>()
  for (const c of list) {
    const e = by.get(c.draftId) ?? { title: c.draftTitle, refs: [] }
    e.refs.push(refString(c.ref))
    by.set(c.draftId, e)
  }
  return [...by.entries()]
})

function submit() {
  void editor.save({ title: title.value.trim(), changesetComment: comment.value.trim() })
}
</script>

<template>
  <Modal v-if="editor.saveDialog" :title="t('dialog.save.title')" width="480px" @close="editor.saveDialog = false">
    <form class="space-y-3 px-5 py-4" data-testid="edit-save-dialog" @submit.prevent="submit">
      <p class="text-[12px] text-fg-2">
        {{ tn('dialog.save.intro', editor.editCount) }}
      </p>
      <label class="block space-y-1">
        <span class="text-[12px] font-bold">{{ t('dialog.save.titleLabel') }}</span>
        <input v-model="title" class="input" required data-testid="edit-save-title" />
      </label>
      <label class="block space-y-1">
        <span class="text-[12px] font-bold">{{ t('dialog.save.comment') }} <span class="font-normal text-fg-3">{{ t('dialog.save.optional') }}</span></span>
        <input v-model="comment" class="input" :placeholder="t('dialog.save.commentPlaceholder')" />
      </label>
    </form>
    <template #footer>
      <button class="btn ml-auto" @click="editor.saveDialog = false">{{ t('common.cancel') }}</button>
      <button class="btn btn-primary" :disabled="editor.saving || !title.trim()" data-testid="edit-save-submit" @click="submit">
        <Spinner v-if="editor.saving" :size="12" /> {{ t('toolbar.saveDraft') }}
      </button>
    </template>
  </Modal>

  <Modal v-if="issues.summary" :title="t('dialog.issues.title')" width="520px" @close="issues.summary.resolve(false)">
    <div class="space-y-3 px-5 py-4 text-[12px]" data-testid="edit-issue-summary">
      <p class="text-fg-2">
        {{ t('dialog.issues.have') }}
        <b v-if="issues.summary.errors.length" class="text-error">{{ tn('toolbar.errors', issues.summary.errors.length) }}</b>
        <template v-if="issues.summary.errors.length && issues.summary.warnings.length"> {{ t('dialog.issues.and') }} </template>
        <b v-if="issues.summary.warnings.length" class="text-warning">{{ tn('toolbar.warnings', issues.summary.warnings.length) }}</b>.
        {{ t('dialog.issues.hint') }}
      </p>
      <ul class="max-h-64 space-y-1 overflow-y-auto">
        <li v-for="i in [...issues.summary.errors, ...issues.summary.warnings]" :key="i.id" class="flex items-start gap-2">
          <span class="mt-1 size-2 shrink-0 rounded-full" :class="i.severity === 'error' ? 'bg-error' : 'bg-warning'" />
          <span>{{ i.message }} <span class="font-mono text-[10.5px] text-fg-3">{{ i.source === 'id' ? i.type : `${i.file}:${i.line}` }}</span></span>
        </li>
      </ul>
    </div>
    <template #footer>
      <button class="btn ml-auto" data-testid="edit-issue-review" @click="issues.review()">{{ t('dialog.issues.review') }}</button>
      <button class="btn btn-primary" data-testid="edit-issue-save-anyway" @click="issues.summary.resolve(true)">{{ t('dialog.issues.saveAnyway') }}</button>
    </template>
  </Modal>

  <ConfirmDialog
    v-if="editor.takeOver"
    :title="t('dialog.takeOver.title')"
    :confirm-label="t('dialog.takeOver.confirm')"
    @confirm="editor.takeOver.resolve(true)"
    @cancel="editor.takeOver.resolve(false)"
  >
    <p>{{ t('dialog.takeOver.body') }}</p>
    <ul class="mt-2 space-y-1" data-testid="edit-takeover">
      <li v-for="[id, d] in takeOverDrafts" :key="id">
        <b class="text-fg">{{ d.title }}</b>: <span class="font-mono text-[11.5px]">{{ d.refs.join(' ') }}</span>
      </li>
    </ul>
  </ConfirmDialog>

  <ConfirmDialog
    v-if="editor.exitConfirm"
    :title="t('dialog.discard.title')"
    :confirm-label="t('dialog.discard.confirm')"
    danger
    @confirm="editor.exit(true)"
    @cancel="editor.exitConfirm = false"
  >
    {{ tn('dialog.discard.body', editor.editCount) }}
  </ConfirmDialog>
</template>
