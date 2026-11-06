<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Draft } from '@/generated/models'
import { useDraftsStore } from '@/stores/drafts'
import { useMapStore } from '@/stores/map'
import { asGeometry, geometryBBox } from '@/lib/geo'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import { useI18n } from '@/i18n'

const { t } = useI18n()
const props = defineProps<{ draft: Draft }>()
const drafts = useDraftsStore()
const mapStore = useMapStore()

const open = ref(false)
const comment = ref('')
const source = ref('')
const saving = ref(false)

function reset() {
  comment.value = props.draft.changesetComment ?? ''
  source.value = props.draft.changesetSource ?? ''
}
watch(
  () => [props.draft.id, props.draft.changesetComment, props.draft.changesetSource],
  reset,
  { immediate: true },
)

const dirty = computed(
  () => comment.value.trim() !== (props.draft.changesetComment ?? '') || source.value.trim() !== (props.draft.changesetSource ?? ''),
)
const scopeBBox = computed(() => geometryBBox(asGeometry(props.draft.scope)))
const areaLabel = computed(() => props.draft.area ?? (scopeBBox.value ? t('drafts.cs.customShape') : null))
const summary = computed(() => props.draft.changesetComment || t('drafts.cs.notSet'))

async function save() {
  if (!dirty.value || saving.value) return
  saving.value = true
  try {
    await drafts.updateChangeset(props.draft.id, comment.value, source.value)
  } finally {
    saving.value = false
  }
}

function showArea() {
  if (scopeBBox.value) mapStore.fitBBox(scopeBBox.value, 17)
}
</script>

<template>
  <div class="mt-2 rounded-md border border-line" data-testid="draft-changeset">
    <button class="flex w-full items-center gap-1.5 px-2.5 py-1.5 text-left text-[12px]" @click="open = !open">
      <Icon :name="open ? 'chevron-down' : 'chevron-right'" :size="13" class="shrink-0 text-fg-3" />
      <span class="text-fg-3">{{ t('drafts.cs.title') }}</span>
      <span class="min-w-0 flex-1 truncate" :class="draft.changesetComment ? 'text-fg' : 'text-fg-3'" :title="summary">{{ summary }}</span>
      <span v-if="areaLabel" class="chip max-w-[40%] shrink-0 bg-surface-3 text-fg-2" :title="t('drafts.cs.areaTitle', { area: areaLabel })">
        <Icon name="pin" :size="11" /><span class="truncate">{{ areaLabel }}</span>
      </span>
    </button>
    <div v-if="open" class="space-y-2 border-t border-line px-2.5 py-2.5">
      <div class="flex items-center gap-2 text-[12px]">
        <span class="w-16 shrink-0 text-fg-3">{{ t('drafts.cs.area') }}</span>
        <span class="min-w-0 flex-1 truncate">{{ areaLabel ?? t('drafts.cs.noArea') }}</span>
        <button v-if="scopeBBox" class="btn btn-ghost btn-sm" :title="t('drafts.cs.zoomTitle')" @click="showArea">
          <Icon name="target" :size="12" /> {{ t('drafts.cs.show') }}
        </button>
      </div>
      <label class="flex items-start gap-2 text-[12px]">
        <span class="mt-1.5 w-16 shrink-0 text-fg-3">{{ t('drafts.cs.comment') }}</span>
        <textarea
          v-model="comment"
          class="input h-auto min-h-14 flex-1 py-1.5"
          rows="2"
          maxlength="255"
          :placeholder="t('drafts.cs.commentPlaceholder')"
          @keydown.enter.exact.prevent="save"
        />
      </label>
      <label class="flex items-center gap-2 text-[12px]">
        <span class="w-16 shrink-0 text-fg-3">{{ t('drafts.cs.source') }}</span>
        <input v-model="source" class="input flex-1" maxlength="255" :placeholder="t('drafts.cs.sourcePlaceholder')" @keydown.enter.prevent="save" />
      </label>
      <div class="flex items-center gap-2">
        <button class="btn btn-primary btn-sm" :disabled="!dirty || saving" @click="save">
          <Spinner v-if="saving" :size="11" /> {{ t('common.save') }}
        </button>
        <button class="btn btn-ghost btn-sm" :disabled="!dirty || saving" @click="reset">{{ t('drafts.cs.revert') }}</button>
        <span class="ml-auto text-[11px] text-fg-3">{{ t('drafts.cs.emptyHint') }}</span>
      </div>
    </div>
  </div>
</template>
