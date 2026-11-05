<script setup lang="ts">
import { computed } from 'vue'
import type { CoverageEntry, HighlightFile } from '@/generated/models'
import { useHighlightsStore, FILTER_PATH } from '@/stores/highlights'
import { useDraftsStore } from '@/stores/drafts'
import { formatArea, formatKm } from '@/lib/scope'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import { useI18n, type MessageKey } from '@/i18n'

const hl = useHighlightsStore()
const drafts = useDraftsStore()
const { t, formatNumber } = useI18n()

const MEASURES: Record<string, MessageKey> = {
  length: 'legend.measure.length',
  area: 'legend.measure.area',
  count: 'legend.measure.count',
}

function measureLabel(m: string): string {
  const k = MEASURES[m]
  return k ? t(k) : m
}

function percent(p: number): string {
  return formatNumber(p / 100, { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 })
}

function scopeLabel(): string {
  const s = hl.scope
  if (!s) return t('map.noArea')
  if (s.kind === 'area') return s.label
  return s.kind === 'polygon' ? t('map.drawnPolygon') : t('map.view')
}

const shown = computed(() => !drafts.reviewing && hl.activeFiles.length > 0)

function value(measure: string, e: CoverageEntry): string {
  if (measure === 'length') return formatKm(e.lengthKm)
  if (measure === 'area') return formatArea(e.areaKm2)
  return formatNumber(e.count)
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
    class="pointer-events-auto absolute top-3 left-3 z-10 w-[min(300px,calc(100%-72px))] overflow-hidden rounded-md border border-line-strong bg-surface text-[12px] shadow-panel"
    data-testid="highlight-legend"
  >
    <button class="flex w-full items-center gap-2 bg-surface-2 px-3 py-1.5 text-left hover:bg-surface-3" @click="hl.legendOpen = !hl.legendOpen">
      <Icon name="layers" :size="14" class="text-fg-2" />
      <span class="font-bold">{{ t('highlight.title') }}</span>
      <span class="truncate text-fg-3">· {{ scopeLabel() }}</span>
      <Spinner v-if="hl.evaluating > 0" :size="11" class="ml-auto text-fg-3" />
      <Icon :name="hl.legendOpen ? 'chevron-up' : 'chevron-down'" :size="13" class="text-fg-3" :class="hl.evaluating > 0 ? '' : 'ml-auto'" />
    </button>
    <div v-if="hl.legendOpen" class="scroll-thin max-h-[42vh] space-y-2.5 overflow-y-auto border-t border-line-strong px-3 pt-2 pb-2.5">
      <section v-for="f in hl.activeFiles" :key="f.path">
        <div class="mb-1 flex items-center gap-1.5">
          <span class="truncate font-bold" :title="f.description ?? f.path">{{ f.path === FILTER_PATH ? t('legend.filter', { title: f.title }) : f.title }}</span>
          <span v-if="f.dimOthers" class="chip shrink-0 bg-surface-3 text-[10px] text-fg-2" title="-ore-dim-others">{{ t('legend.dimmed') }}</span>
          <Spinner v-if="hl.stats.get(f.path)?.loading" :size="11" class="ml-auto shrink-0 text-fg-3" />
        </div>
        <table v-if="rows(f).length" class="w-full tabular-nums">
          <tbody>
            <tr v-for="r in rows(f)" :key="r.label">
              <td class="w-4 py-0.5"><span class="block h-1.5 w-3.5 rounded-sm" :style="{ background: r.color ?? '#999' }" /></td>
              <td class="truncate py-0.5 pr-2 text-fg">{{ r.label }}</td>
              <td v-if="r.value !== null" class="py-0.5 text-right text-[11.5px]">{{ r.value }}</td>
              <td v-if="r.percent !== null" class="w-12 py-0.5 text-right text-[11px] text-fg-3">{{ percent(r.percent) }}</td>
            </tr>
            <tr v-if="hl.stats.get(f.path)?.data" class="border-t border-line">
              <td />
              <td class="pt-1 font-bold text-fg-2">{{ t('legend.total') }}</td>
              <td class="pt-1 text-right text-[11.5px] font-bold">
                {{ value(hl.stats.get(f.path)!.data!.stats, hl.stats.get(f.path)!.data!.total) }}
              </td>
              <td />
            </tr>
          </tbody>
        </table>
        <div v-else class="flex gap-1">
          <span v-for="c in swatches(f)" :key="c" class="h-1.5 w-5 rounded-sm" :style="{ background: c }" />
        </div>
        <p v-if="hl.statsNeedsZoom && (f.legend.length || f.stats)" class="mt-1 text-[11px] text-fg-3">{{ t('legend.zoomForCoverage') }}</p>
        <p v-else-if="hl.stats.get(f.path)?.error && f.path !== FILTER_PATH" class="mt-1 text-[11px] text-error">{{ hl.stats.get(f.path)?.error }}</p>
        <p v-else-if="hl.stats.get(f.path)?.data" class="mt-0.5 text-[11px] text-fg-3">
          {{
            t('legend.statsOver', {
              stats: measureLabel(hl.stats.get(f.path)!.data!.stats),
              area: formatNumber(hl.stats.get(f.path)!.data!.scopeAreaKm2, { minimumFractionDigits: 1, maximumFractionDigits: 1 }),
              ms: formatNumber(hl.stats.get(f.path)!.data!.tookMs),
            })
          }}
        </p>
      </section>
      <p v-if="hl.evalNeedsZoom" class="text-[11px] text-changed">{{ t('legend.serverZoom') }}</p>
      <p v-else-if="hl.evalTruncated" class="text-[11px] text-changed">{{ t('legend.truncated') }}</p>
    </div>
  </div>
</template>
