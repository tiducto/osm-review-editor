<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useEditorStore } from '@/stores/editor'
import { useMapStore } from '@/stores/map'
import { refString } from '@/lib/osm'
import Modal from '../Modal.vue'
import ConfirmDialog from '../ConfirmDialog.vue'
import Spinner from '../Spinner.vue'

const editor = useEditorStore()
const mapStore = useMapStore()

const title = ref('')
const comment = ref('')

watch(
  () => editor.saveDialog,
  (open) => {
    if (!open) return
    const where = mapStore.area?.name ?? mapStore.scopeName
    const date = new Date().toISOString().slice(0, 10)
    title.value ||= where ? `Edits in ${where}, ${date}` : `Map edits, ${date}`
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
  <Modal v-if="editor.saveDialog" title="Save edits to a new draft" width="480px" @close="editor.saveDialog = false">
    <form class="space-y-3 px-5 py-4" data-testid="edit-save-dialog" @submit.prevent="submit">
      <p class="text-[12.5px] text-fg-2">
        {{ editor.editCount }} edited element{{ editor.editCount === 1 ? '' : 's' }} become a draft you review object by object. Nothing is uploaded.
      </p>
      <label class="block space-y-1">
        <span class="text-[12px] font-medium">Draft title</span>
        <input v-model="title" class="input" required data-testid="edit-save-title" />
      </label>
      <label class="block space-y-1">
        <span class="text-[12px] font-medium">Changeset comment <span class="font-normal text-fg-3">(optional)</span></span>
        <input v-model="comment" class="input" placeholder="e.g. Split Kateřinská street, added footway" />
      </label>
    </form>
    <template #footer>
      <button class="btn ml-auto" @click="editor.saveDialog = false">Cancel</button>
      <button class="btn btn-primary" :disabled="editor.saving || !title.trim()" data-testid="edit-save-submit" @click="submit">
        <Spinner v-if="editor.saving" :size="12" /> Save draft
      </button>
    </template>
  </Modal>

  <ConfirmDialog
    v-if="editor.takeOver"
    title="Move changes from other drafts?"
    confirm-label="Move into this draft"
    @confirm="editor.takeOver.resolve(true)"
    @cancel="editor.takeOver.resolve(false)"
  >
    <p>Some edited elements already have open changes in other drafts. Saving moves those changes here and rejects them there.</p>
    <ul class="mt-2 space-y-1" data-testid="edit-takeover">
      <li v-for="[id, d] in takeOverDrafts" :key="id">
        <b class="text-fg">{{ d.title }}</b>: <span class="font-mono text-[11.5px]">{{ d.refs.join(' ') }}</span>
      </li>
    </ul>
  </ConfirmDialog>

  <ConfirmDialog
    v-if="editor.exitConfirm"
    title="Discard your edits?"
    confirm-label="Discard"
    danger
    @confirm="editor.exit(true)"
    @cancel="editor.exitConfirm = false"
  >
    You have {{ editor.editCount }} unsaved edit{{ editor.editCount === 1 ? '' : 's' }}. Leaving edit mode throws them away.
  </ConfirmDialog>
</template>
