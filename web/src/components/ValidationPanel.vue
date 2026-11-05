<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Severity } from '@/generated/models'
import { useScansStore } from '@/stores/scans'
import { useMapStore } from '@/stores/map'
import { useAppStore } from '@/stores/app'
import { useDraftsStore } from '@/stores/drafts'
import { severityColor } from '@/lib/colors'
import { refString } from '@/lib/osm'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'

const scans = useScansStore()
const mapStore = useMapStore()
const app = useAppStore()
const drafts = useDraftsStore()

const sevs: Severity[] = ['error', 'warning', 'info']
const setupOpen = ref(true)
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

watch(
  () => scans.running,
  (r) => {
    if (timer) clearInterval(timer)
    timer = r ? setInterval(() => (now.value = Date.now()), 250) : null
  },
)
watch(
  () => scans.report?.scan.id,
  (id) => {
    if (id) setupOpen.value = false
  },
)

const elapsed = computed(() => ((now.value - scans.runStarted) / 1000).toFixed(1))
const scopeHelp = computed(() => (scans.scope ? '' : 'Pick an area or zoom in to 13+ to scan the map view'))
const shownLimit = ref(300)
const shown = computed(() => scans.listed.slice(0, shownLimit.value))
const scan = computed(() => scans.report?.scan ?? null)
const builtInSelectable = computed(() => scans.builtIn)

function pickArea(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  if (v === '__view') void mapStore.setArea(null)
  else if (v === '__draw') mapStore.drawing = true
  else void mapStore.setArea(v)
}

function when(s: string) {
  return new Date(s).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function fileName(p: string) {
  return p.replace(/^checks\//, '').replace(/\.validator\.mapcss$/, '')
}

function togglePick(id: number) {
  if (scans.picked.has(id)) scans.picked.delete(id)
  else scans.picked.add(id)
}

onMounted(() => {
  void scans.init()
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="flex h-full min-h-0 flex-col" data-testid="validation">
    <section class="shrink-0 border-b border-line">
      <button class="flex w-full items-center gap-2 px-3 py-2 text-left" @click="setupOpen = !setupOpen">
        <Icon :name="setupOpen ? 'chevron-down' : 'chevron-right'" :size="12" class="text-fg-3" />
        <span class="section-title">New scan</span>
        <span class="truncate text-[11.5px] text-fg-3">· {{ scans.scope?.label ?? 'no area' }}</span>
      </button>
      <div v-if="setupOpen" class="space-y-2.5 px-3 pb-3">
        <div class="relative">
          <Icon name="pin" :size="14" class="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-fg-3" />
          <select class="input appearance-none pr-7 pl-7" :value="mapStore.area?.name ?? (mapStore.polygon ? '__poly' : '__view')" data-testid="scan-area" @change="pickArea">
            <option value="__view">Map view</option>
            <option v-if="mapStore.polygon" value="__poly">Drawn polygon</option>
            <option value="__draw">Draw a polygon…</option>
            <option v-for="a in app.areas" :key="a.name" :value="a.name">{{ a.name }}</option>
          </select>
          <Icon name="chevron-down" :size="13" class="pointer-events-none absolute top-1/2 right-2 -translate-y-1/2 text-fg-3" />
        </div>
        <p v-if="scopeHelp" class="text-[11.5px] text-changed">{{ scopeHelp }}</p>

        <div>
          <div class="mb-1 text-[11.5px] font-medium text-fg-2">Check files</div>
          <label v-for="f in scans.checkFiles" :key="f.path" class="flex items-center gap-2 py-0.5 text-[12px]">
            <input
              type="checkbox"
              class="accent-[var(--accent)]"
              :checked="scans.selectedFiles.has(f.path)"
              :disabled="f.diagnostics.some((d) => d.severity === 'error')"
              :data-testid="`scan-file-${f.path}`"
              @change="scans.toggleFile(f.path)"
            />
            <span class="min-w-0 flex-1 truncate font-mono text-[11.5px]" :title="f.path">{{ fileName(f.path) }}</span>
            <span v-if="f.origin?.builtIn" class="chip bg-surface-3 text-[10px] text-fg-3">built-in</span>
            <span v-else-if="f.origin" class="chip bg-surface-3 text-[10px] text-fg-3">remote</span>
          </label>
          <p v-if="!scans.checkFiles.length" class="text-[11.5px] text-fg-3">No check files.</p>
        </div>
        <div>
          <div class="mb-1 text-[11.5px] font-medium text-fg-2">Built-in rules</div>
          <label v-for="r in builtInSelectable" :key="r.id" class="flex items-center gap-2 py-0.5 text-[12px]" :title="r.description">
            <input type="checkbox" class="accent-[var(--accent)]" :checked="scans.selectedBuiltIn.has(r.id)" @change="scans.toggleBuiltIn(r.id)" />
            <span class="min-w-0 flex-1 truncate">{{ r.title }}</span>
            <span class="size-1.5 shrink-0 rounded-full" :style="{ background: severityColor[r.severity] }" />
          </label>
        </div>
        <button
          class="btn btn-primary w-full justify-center"
          :disabled="!scans.scope || scans.running || (!scans.selectedFiles.size && !scans.selectedBuiltIn.size)"
          data-testid="run-scan"
          @click="scans.run()"
        >
          <Spinner v-if="scans.running" :size="12" />
          <Icon v-else name="bolt" :size="13" />
          {{ scans.running ? `Scanning… ${elapsed} s` : 'Run scan' }}
        </button>
        <div v-if="scans.running" class="h-1 overflow-hidden rounded-full bg-surface-3">
          <div class="h-full w-1/3 animate-[ore-indet_1.2s_ease-in-out_infinite] rounded-full bg-accent" />
        </div>
        <p v-if="scans.runError" class="text-[11.5px] text-error">{{ scans.runError }}</p>
      </div>
    </section>

    <div class="scroll-thin min-h-0 flex-1 overflow-y-auto">
      <section v-if="scans.loadingReport" class="flex items-center gap-2 px-3 py-3 text-fg-3"><Spinner :size="12" /> Loading report…</section>

      <section v-else-if="scan" class="space-y-2.5 border-b border-line px-3 py-3" data-testid="scan-report">
        <div class="flex items-center gap-2">
          <span class="section-title">Report</span>
          <span class="truncate text-[11.5px] text-fg-3">{{ scan.areaName ?? 'map area' }} · {{ when(scan.startedAt) }}</span>
          <button class="btn btn-ghost btn-icon btn-sm ml-auto w-6" title="Close report" @click="scans.closeReport()"><Icon name="x" :size="12" /></button>
        </div>
        <div class="grid grid-cols-3 gap-1.5 text-center">
          <button
            v-for="s in sevs"
            :key="s"
            class="rounded-md border px-1 py-1.5 transition-colors"
            :class="scans.severities[s] ? 'border-line-strong bg-surface-2' : 'border-line text-fg-3 opacity-60'"
            @click="scans.severities[s] = !scans.severities[s]"
          >
            <div class="font-mono text-[15px] font-semibold tabular-nums" :style="{ color: severityColor[s] }">
              {{ (s === 'error' ? scan.errors : s === 'warning' ? scan.warnings : scan.infos).toLocaleString() }}
            </div>
            <div class="text-[10.5px] text-fg-3">{{ s }}s</div>
          </button>
        </div>
        <div v-if="scans.diff" class="flex rounded-md border border-line p-0.5 text-[11.5px]" data-testid="scan-diff">
          <button class="flex-1 rounded px-1 py-1" :class="scans.view === 'all' && 'bg-surface-3 font-medium'" @click="scans.view = 'all'">
            All {{ scan.issueCount.toLocaleString() }}
          </button>
          <button class="flex-1 rounded px-1 py-1" :class="scans.view === 'new' && 'bg-surface-3 font-medium'" @click="scans.view = 'new'">
            <span class="text-changed">New {{ scans.diff.newCount.toLocaleString() }}</span>
          </button>
          <button class="flex-1 rounded px-1 py-1" :class="scans.view === 'resolved' && 'bg-surface-3 font-medium'" @click="scans.view = 'resolved'">
            <span class="text-added">Resolved {{ scans.diff.resolvedCount.toLocaleString() }}</span>
          </button>
        </div>
        <p v-if="scans.diff" class="text-[11px] text-fg-3">
          {{ scans.diff.against ? `${scans.diff.unchangedCount.toLocaleString()} unchanged since the previous scan` : 'No earlier scan of this area and rule set: every issue is new' }}
        </p>
        <table class="w-full text-[11.5px] tabular-nums">
          <tbody>
            <tr
              v-for="g in scans.groups"
              :key="g.group"
              class="cursor-pointer hover:bg-surface-2"
              :class="scans.group === g.group && 'bg-accent-soft'"
              @click="scans.group = scans.group === g.group ? null : g.group"
            >
              <td class="max-w-0 truncate py-0.5 pr-2" :title="g.group">{{ g.group }}</td>
              <td class="w-9 text-right font-mono" :style="{ color: g.error ? severityColor.error : undefined }">{{ g.error || '' }}</td>
              <td class="w-9 text-right font-mono" :style="{ color: g.warning ? severityColor.warning : undefined }">{{ g.warning || '' }}</td>
              <td class="w-9 text-right font-mono" :style="{ color: g.info ? severityColor.info : undefined }">{{ g.info || '' }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-if="scan && !scans.loadingReport" class="py-2">
        <div class="space-y-1.5 px-3 pb-2">
          <input v-model="scans.text" class="input" placeholder="Filter issues…" data-testid="issue-filter" />
          <div class="flex items-center gap-1.5 text-[11.5px]">
            <span class="text-fg-3">{{ scans.listed.length.toLocaleString() }} shown</span>
            <button v-if="scans.fixable.length" class="ml-auto text-accent hover:underline" @click="scans.pickAllFixable()">Select fixable ({{ scans.fixable.length }})</button>
            <button v-if="scans.picked.size" class="text-fg-3 hover:text-fg" @click="scans.picked.clear()">None</button>
          </div>
          <button
            v-if="scans.picked.size"
            class="btn btn-primary w-full justify-center"
            :disabled="scans.creatingDraft || drafts.busy"
            data-testid="draft-from-issues"
            @click="scans.draftFromPicked()"
          >
            <Spinner v-if="scans.creatingDraft" :size="12" /><Icon v-else name="plus" :size="12" /> Create draft from {{ scans.picked.size }} issue{{ scans.picked.size === 1 ? '' : 's' }}
          </button>
        </div>
        <ul data-testid="issue-list">
          <li
            v-for="l in shown"
            :key="`${l.state}-${l.issue.id}`"
            class="group flex items-start gap-2 border-l-2 py-1.5 pr-3 pl-2.5 text-[12px] hover:bg-surface-2"
            :class="scans.focused === l.issue.id ? 'border-accent bg-surface-2' : 'border-transparent'"
          >
            <input
              v-if="l.state !== 'resolved' && (l.issue.fix || l.issue.deleteObject)"
              type="checkbox"
              class="mt-0.5 accent-[var(--accent)]"
              :checked="scans.picked.has(l.issue.id)"
              :title="l.issue.deleteObject ? 'Fix: delete the object' : 'Has a fix'"
              @change="togglePick(l.issue.id)"
            />
            <span v-else class="mt-1 size-3 shrink-0" />
            <button class="min-w-0 flex-1 text-left" @click="scans.focus(l.issue)">
              <span class="flex items-center gap-1.5">
                <span class="size-2 shrink-0 rounded-full" :style="{ background: l.state === 'resolved' ? '#94a3b8' : severityColor[l.issue.severity] }" />
                <span class="truncate" :class="l.state === 'resolved' && 'text-fg-3 line-through'" :title="l.issue.message">{{ l.issue.message }}</span>
              </span>
              <span class="mt-0.5 flex items-center gap-1.5 pl-3.5 text-[10.5px] text-fg-3">
                <span class="font-mono">{{ refString(l.issue.ref) }}</span>
                <span class="truncate">{{ l.issue.group ?? l.issue.rule }}</span>
                <span v-if="l.state === 'new' && scans.diff?.against" class="chip bg-changed-soft px-1 text-[9.5px] text-changed">new</span>
                <span v-if="l.issue.fix" class="chip bg-added-soft px-1 text-[9.5px] text-added">fix</span>
              </span>
            </button>
          </li>
        </ul>
        <button v-if="scans.listed.length > shown.length" class="mx-3 my-2 text-[12px] text-accent hover:underline" @click="shownLimit += 300">
          Show more ({{ (scans.listed.length - shown.length).toLocaleString() }})
        </button>
      </section>

      <section class="border-t border-line px-3 py-3">
        <div class="mb-1.5 flex items-center gap-2">
          <span class="section-title">Previous scans</span>
          <button class="btn btn-ghost btn-icon btn-sm ml-auto w-6" title="Reload" @click="scans.loadHistory()"><Icon name="refresh" :size="12" /></button>
        </div>
        <p v-if="!scans.history.length" class="text-[12px] text-fg-3">No scans yet.</p>
        <ul class="space-y-0.5">
          <li v-for="s in scans.history" :key="s.id">
            <button
              class="flex w-full items-center gap-2 rounded px-1.5 py-1 text-left text-[12px] hover:bg-surface-2"
              :class="scan?.id === s.id && 'bg-accent-soft'"
              @click="scans.openScan(s.id)"
            >
              <span class="min-w-0 flex-1 truncate">{{ s.areaName ?? s.scope.replace(/#.*$/, '') }}</span>
              <span v-if="s.trigger === 'mirror_swap'" class="chip bg-surface-3 text-[10px] text-fg-3" title="Re-run after a mirror update">auto</span>
              <span v-if="s.status === 'failed'" class="text-error" :title="s.error ?? ''"><Icon name="alert" :size="12" /></span>
              <span v-else-if="s.status === 'running'"><Spinner :size="11" /></span>
              <span v-else class="font-mono text-[11px] tabular-nums text-fg-2">{{ s.issueCount.toLocaleString() }}</span>
              <span class="shrink-0 text-[10.5px] text-fg-3">{{ when(s.startedAt) }}</span>
            </button>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style>
@keyframes ore-indet {
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(300%);
  }
}
</style>
