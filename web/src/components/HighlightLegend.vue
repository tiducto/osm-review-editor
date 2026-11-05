<script setup lang="ts">
import { computed } from 'vue'
import type { CoverageEntry, HighlightFile } from '@/generated/models'
import { useHighlightsStore, FILTER_PATH } from '@/stores/highlights'
import { useDraftsStore } from '@/stores/drafts'
import { formatArea, formatKm } from '@/lib/scope'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'

const hl = useHighlightsStore()
const drafts = useDraftsStore()

const shown = computed(() => !drafts.reviewing && hl.activeFiles.length > 0)

function value(measure: string, e: CoverageEntry): string {
  if (measure === 'length') return formatKm(e.lengthKm)
  if (measure === 'area') return formatArea(e.areaKm2)
  return e.count.toLocaleString()
}

function rows(f: HighlightFile) {
  const s = hl.stats.get(f.path)?.data
  if (s) return s.entries.map((e) => ({ label: e.label, color: e.color, value: value(s.stats, e), percent: e.percent }))
  return f.legend.map((l) => ({ label: l.label, color: l.color, value: null as string | null, percent: null as number | null }))
}

function swatches(f: HighlightFile): string[] {
  return [...new Set(f.rules.map((r) => r.color).filter((c): c is string => !!c))].slice(0, 6)
}
</script>

<template>
  <div
    v-if="shown"
    class="pointer-events-auto absolute top-3 left-3 z-10 w-[min(300px,calc(100%-72px))] rounded-lg border border-line bg-surface/95 text-[12px] shadow-panel backdrop-blur"
    data-testid="highlight-legend"
  >
    <button class="flex w-full items-center gap-2 px-3 py-1.5 text-left" @click="hl.legendOpen = !hl.legendOpen">
      <Icon name="layers" :size="13" class="text-fg-3" />
      <span class="font-semibold">Highlights</span>
      <span class="truncate text-fg-3">· {{ hl.scope?.label ?? 'no area' }}</span>
      <Spinner v-if="hl.evaluating > 0" :size="11" class="ml-auto text-fg-3" />
      <Icon :name="hl.legendOpen ? 'chevron-up' : 'chevron-down'" :size="13" class="text-fg-3" :class="hl.evaluating > 0 ? '' : 'ml-auto'" />
    </button>
    <div v-if="hl.legendOpen" class="scroll-thin max-h-[42vh] space-y-2.5 overflow-y-auto border-t border-line px-3 pt-2 pb-2.5">
      <section v-for="f in hl.activeFiles" :key="f.path">
        <div class="mb-1 flex items-center gap-1.5">
          <span class="truncate font-medium" :title="f.description ?? f.path">{{ f.path === FILTER_PATH ? `Filter: ${f.title}` : f.title }}</span>
          <span v-if="f.dimOthers" class="chip shrink-0 bg-surface-3 text-[10px] text-fg-3" title="-ore-dim-others">dimmed</span>
          <Spinner v-if="hl.stats.get(f.path)?.loading" :size="11" class="ml-auto shrink-0 text-fg-3" />
        </div>
        <table v-if="rows(f).length" class="w-full tabular-nums">
          <tbody>
            <tr v-for="r in rows(f)" :key="r.label">
              <td class="w-4 py-0.5"><span class="block h-1.5 w-3.5 rounded-full" :style="{ background: r.color ?? '#999' }" /></td>
              <td class="truncate py-0.5 pr-2 text-fg-2">{{ r.label }}</td>
              <td v-if="r.value !== null" class="py-0.5 text-right font-mono text-[11px]">{{ r.value }}</td>
              <td v-if="r.percent !== null" class="w-12 py-0.5 text-right font-mono text-[11px] text-fg-3">{{ r.percent.toFixed(1) }}%</td>
            </tr>
            <tr v-if="hl.stats.get(f.path)?.data" class="border-t border-line">
              <td />
              <td class="pt-1 text-fg-3">total</td>
              <td class="pt-1 text-right font-mono text-[11px]">
                {{ value(hl.stats.get(f.path)!.data!.stats, hl.stats.get(f.path)!.data!.total) }}
              </td>
              <td />
            </tr>
          </tbody>
        </table>
        <div v-else class="flex gap-1">
          <span v-for="c in swatches(f)" :key="c" class="h-1.5 w-5 rounded-full" :style="{ background: c }" />
        </div>
        <p v-if="hl.statsNeedsZoom && (f.legend.length || f.stats)" class="mt-1 text-[11px] text-fg-3">Zoom in or pick an area for coverage numbers</p>
        <p v-else-if="hl.stats.get(f.path)?.error && f.path !== FILTER_PATH" class="mt-1 text-[11px] text-error">{{ hl.stats.get(f.path)?.error }}</p>
        <p v-else-if="hl.stats.get(f.path)?.data" class="mt-0.5 text-[10.5px] text-fg-3">
          {{ hl.stats.get(f.path)!.data!.stats }} over {{ hl.stats.get(f.path)!.data!.scopeAreaKm2.toFixed(1) }} km² · {{ hl.stats.get(f.path)!.data!.tookMs }} ms
        </p>
      </section>
      <p v-if="hl.evalNeedsZoom" class="text-[11px] text-changed">Some rules run on the server: zoom in to see them</p>
      <p v-else-if="hl.evalTruncated" class="text-[11px] text-changed">Server results were truncated</p>
    </div>
  </div>
</template>
