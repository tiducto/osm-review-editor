<script setup lang="ts">
import { ref } from 'vue'
import { useMapStore } from '@/stores/map'
import ImageryEditor from '../ImageryEditor.vue'
import Icon from '../Icon.vue'
import { useI18n } from '@/i18n'

const { t } = useI18n()
const mapStore = useMapStore()
const editing = ref(false)
</script>

<template>
  <div class="scroll-thin h-full overflow-y-auto bg-surface-2">
    <section class="space-y-2 px-4 py-3">
      <div class="flex items-center">
        <h3 class="section-title">{{ t('shell.pane.imagery') }}</h3>
        <button v-if="!editing" class="btn btn-ghost btn-sm ml-auto" data-testid="background-edit-imagery" @click="editing = true">
          <Icon name="edit" :size="12" /> {{ t('shell.pane.editSources') }}
        </button>
      </div>
      <ImageryEditor v-if="editing" @close="editing = false" />
      <div v-else class="card overflow-hidden" data-testid="imagery-list">
        <label class="flex cursor-pointer items-center gap-2.5 border-b border-line px-3 py-2 transition-colors hover:bg-surface-3">
          <input type="radio" name="background-imagery" :checked="!mapStore.imageryId" @change="mapStore.setImagery(null)" />
          <span>{{ t('common.none') }}</span>
        </label>
        <label
          v-for="s in mapStore.allImagery"
          :key="s.id"
          class="flex cursor-pointer items-center gap-2.5 border-b border-line px-3 py-2 transition-colors last:border-b-0 hover:bg-surface-3"
          :class="mapStore.imageryId === s.id && 'bg-accent-soft'"
          :data-testid="`imagery-${s.id}`"
        >
          <input type="radio" name="background-imagery" :checked="mapStore.imageryId === s.id" @change="mapStore.setImagery(s.id)" />
          <span class="min-w-0 flex-1 truncate" :title="s.name">{{ s.name }}</span>
          <span class="chip bg-surface-3 font-mono text-fg-3 uppercase">{{ s.type }}</span>
          <span class="text-[11px] text-fg-3">z{{ s.maxZoom }}</span>
        </label>
      </div>
    </section>
  </div>
</template>
