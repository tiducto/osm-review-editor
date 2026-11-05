<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Rule, Severity } from '@/generated/models'
import { useRulesStore } from '@/stores/rules'
import { useMapStore } from '@/stores/map'
import { useDraftsStore } from '@/stores/drafts'
import { severityColor } from '@/lib/colors'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'

const rules = useRulesStore()
const mapStore = useMapStore()
const drafts = useDraftsStore()

const expanded = ref<string | null>(null)
const ruleQuery = ref('')
const sevs: Severity[] = ['error', 'warning', 'info']
const sevLabel: Record<Severity, string> = { error: 'Errors', warning: 'Warnings', info: 'Info' }

const listed = computed(() => {
  const q = ruleQuery.value.trim().toLowerCase()
  const list = q ? rules.rules.filter((r) => r.title.toLowerCase().includes(q) || r.id.toLowerCase().includes(q)) : rules.rules
  return [...list].sort((a, b) => (rules.countFor(b.id)?.count ?? -1) - (rules.countFor(a.id)?.count ?? -1) || a.title.localeCompare(b.title))
})

const totalShown = computed(() => rules.shownIssues.length)
const scopeLabel = computed(() => mapStore.scopeName ?? (rules.needsZoom ? 'Zoom in or pick an area' : 'Visible map'))

const filterExamples = ['highway=footway !surface', 'footway=sidewalk', 'public_transport=platform !tactile_paving']

function createDraft(r: Rule) {
  void drafts.fromRule(r.id)
}
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex h-10 shrink-0 items-center gap-2 border-b border-line px-3">
      <Icon name="layers" :size="15" class="text-fg-3" />
      <h2 class="text-[13px] font-semibold">Layers &amp; Rules</h2>
      <button class="btn btn-ghost btn-icon btn-sm ml-auto w-6" title="Re-run rules" :disabled="!rules.scope" @click="rules.refresh()">
        <Icon name="refresh" :size="13" />
      </button>
    </div>

    <div class="scroll-thin min-h-0 flex-1 overflow-y-auto">
      <section class="space-y-2 border-b border-line px-3 py-3">
        <div class="section-title">Imagery</div>
        <div class="relative">
          <Icon name="image" :size="14" class="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-fg-3" />
          <select
            class="input appearance-none pr-7 pl-7"
            :value="mapStore.imageryId ?? ''"
            @change="mapStore.setImagery(($event.target as HTMLSelectElement).value || null)"
          >
            <option value="">None</option>
            <option v-for="s in mapStore.allImagery" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <Icon name="chevron-down" :size="13" class="pointer-events-none absolute top-1/2 right-2 -translate-y-1/2 text-fg-3" />
        </div>
      </section>

      <section class="space-y-2.5 border-b border-line px-3 py-3">
        <div class="flex items-center">
          <span class="section-title">Filter</span>
          <span class="ml-auto text-[11.5px] text-fg-3">{{ totalShown.toLocaleString() }} shown</span>
        </div>
        <div class="flex gap-1">
          <button
            v-for="s in sevs"
            :key="s"
            class="chip h-6 flex-1 justify-center border transition-colors"
            :class="rules.severities[s] ? 'border-line-strong bg-surface-2 text-fg' : 'border-line bg-transparent text-fg-3 line-through'"
            :aria-pressed="rules.severities[s]"
            @click="rules.severities[s] = !rules.severities[s]"
          >
            <span class="size-2 rounded-full" :style="{ background: severityColor[s], opacity: rules.severities[s] ? 1 : 0.35 }" />
            {{ sevLabel[s] }}
          </button>
        </div>
        <div>
          <div class="relative">
            <Icon name="filter" :size="13" class="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-fg-3" />
            <input
              v-model="rules.tagFilterText"
              class="input pl-7 font-mono text-[12px]"
              :class="rules.tagFilter.error && 'border-error'"
              placeholder="highway=footway !surface"
              spellcheck="false"
            />
          </div>
          <p v-if="rules.tagFilter.error" class="mt-1 text-[11.5px] text-error">{{ rules.tagFilter.error }}</p>
          <div v-else-if="!rules.tagFilterText" class="mt-1.5 flex flex-wrap gap-1">
            <button
              v-for="ex in filterExamples"
              :key="ex"
              class="rounded bg-surface-2 px-1.5 py-0.5 font-mono text-[10.5px] text-fg-3 hover:bg-surface-3 hover:text-fg-2"
              @click="rules.tagFilterText = ex"
            >
              {{ ex }}
            </button>
          </div>
        </div>
      </section>

      <section class="py-2">
        <div class="flex items-center gap-2 px-3 pb-1.5">
          <span class="section-title">Rules</span>
          <span class="truncate text-[11.5px] text-fg-3" :title="scopeLabel">· {{ scopeLabel }}</span>
          <button class="ml-auto text-[11.5px] text-fg-3 hover:text-accent" @click="rules.showAll()">Show all</button>
        </div>
        <div v-if="rules.rules.length > 8" class="px-3 pb-2">
          <input v-model="ruleQuery" class="input" placeholder="Find a rule…" />
        </div>
        <div v-if="!rules.loaded" class="flex items-center gap-2 px-3 py-2 text-fg-3"><Spinner :size="12" /> Loading rules…</div>
        <div v-else-if="!rules.rules.length" class="px-3 py-2 text-[12px] text-fg-3">No rules are installed.</div>

        <ul>
          <li
            v-for="r in listed"
            :key="r.id"
            class="group border-l-2 transition-colors"
            :class="expanded === r.id ? 'border-accent bg-surface-2' : 'border-transparent hover:bg-surface-2'"
          >
            <div class="flex items-center gap-2 py-1.5 pr-2 pl-2.5">
              <button
                class="grid size-4 shrink-0 place-items-center rounded border-2 transition-colors"
                :style="{
                  borderColor: rules.colorOf.get(r.id),
                  background: rules.isVisible(r.id) ? rules.colorOf.get(r.id) : 'transparent',
                }"
                :title="rules.isVisible(r.id) ? 'Hide layer' : 'Show layer'"
                :aria-pressed="rules.isVisible(r.id)"
                @click="rules.toggle(r.id)"
              >
                <Icon v-if="rules.isVisible(r.id)" name="check" :size="10" :stroke="3.5" class="text-white" />
              </button>
              <button class="min-w-0 flex-1 text-left" @click="expanded = expanded === r.id ? null : r.id">
                <span class="block truncate" :class="rules.isVisible(r.id) ? 'text-fg' : 'text-fg-3'" :title="r.title">{{ r.title }}</span>
              </button>
              <span class="size-1.5 shrink-0 rounded-full" :style="{ background: severityColor[r.severity] }" :title="r.severity" />
              <Spinner v-if="rules.loading.has(r.id)" :size="12" class="text-fg-3" />
              <span v-else-if="rules.errors.get(r.id)" class="text-error" :title="rules.errors.get(r.id)"><Icon name="alert" :size="13" /></span>
              <span
                v-else-if="rules.countFor(r.id)"
                class="min-w-7 rounded-full px-1.5 text-center font-mono text-[11px] tabular-nums"
                :class="rules.countFor(r.id)!.count ? 'bg-surface-3 text-fg' : 'text-fg-3'"
                :title="`${rules.countFor(r.id)!.shown} shown of ${rules.countFor(r.id)!.count}${rules.countFor(r.id)!.truncated ? ' (truncated)' : ''}`"
              >
                <template v-if="rules.tagFilter.conds.length">{{ rules.countFor(r.id)!.shown }}/</template>{{ rules.countFor(r.id)!.count.toLocaleString()
                }}<template v-if="rules.countFor(r.id)!.truncated">+</template>
              </span>
            </div>
            <div v-if="expanded === r.id" class="space-y-2 pr-3 pb-3 pl-9">
              <p class="text-[12px] text-fg-2">{{ r.description || 'No description.' }}</p>
              <div class="flex flex-wrap items-center gap-1.5 text-[11px]">
                <span class="chip bg-surface-3 font-mono text-fg-3">{{ r.id }}</span>
                <span class="chip" :class="r.confidence === 'deterministic' ? 'bg-added-soft text-added' : 'bg-changed-soft text-changed'">
                  {{ r.confidence === 'deterministic' ? 'Deterministic' : 'Needs review' }}
                </span>
                <span v-if="!r.hasFix" class="chip bg-surface-3 text-fg-3">Report only</span>
                <span v-if="!r.enabled" class="chip bg-surface-3 text-fg-3">Disabled on server</span>
              </div>
              <div class="flex gap-1.5">
                <button
                  v-if="r.hasFix"
                  class="btn btn-primary btn-sm"
                  :disabled="!rules.scope || drafts.busy"
                  :title="rules.scope ? `Create a draft with this rule's fixes in ${mapStore.area?.name ?? (mapStore.polygon ? 'the drawn polygon' : 'the visible map')}` : 'Pick an area or zoom in first'"
                  @click="createDraft(r)"
                >
                  <Icon name="plus" :size="12" /> Create draft
                </button>
                <button class="btn btn-sm" @click="rules.showOnly(r.id)">Only this</button>
              </div>
            </div>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>
