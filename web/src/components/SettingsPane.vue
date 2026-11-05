<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { McpInfo, OsmTarget } from '@/generated/models'
import { api } from '@/api/client'
import { useAppStore } from '@/stores/app'
import { useMapStore } from '@/stores/map'
import { dateTime, relativeTime } from '@/lib/format'
import ConfirmDialog from './ConfirmDialog.vue'
import ImageryEditor from './ImageryEditor.vue'
import Icon from './Icon.vue'
import { LOCALES, useI18n, type MessageKey } from '@/i18n'

const { t, locale, setLocale } = useI18n()
const app = useAppStore()
const mapStore = useMapStore()
const confirmProd = ref(false)
const editingImagery = ref(false)
const sidecar = computed(() => app.mirror?.sidecar ?? null)
const sidecarResultClass = computed(() =>
  sidecar.value?.result === 'ok' ? 'bg-added' : sidecar.value?.result === 'failed' ? 'bg-error' : 'bg-fg-3',
)
const copied = ref<string | null>(null)

const mcp = ref<McpInfo | null>(null)
const showToken = ref(false)
onMounted(async () => {
  try {
    mcp.value = await api.mcpInfo()
  } catch {}
})

function absolute(u: string): string {
  return u.startsWith('/') ? `${location.origin}${u}` : u
}
const mcpUrl = computed(() => absolute(mcp.value?.url ?? app.status?.mcpUrl ?? '/mcp'))
const claudeCmd = computed(() => mcp.value?.claudeCodeCommand ?? `claude mcp add --transport http osm-review ${mcpUrl.value}`)
const maskedToken = computed(() => (mcp.value?.token ? (showToken.value ? mcp.value.token : '•'.repeat(Math.min(32, mcp.value.token.length))) : ''))
const shownCmd = computed(() => (mcp.value?.token && !showToken.value ? claudeCmd.value.split(mcp.value.token).join(maskedToken.value) : claudeCmd.value))

function pickTarget(target: OsmTarget) {
  if (target === app.target) return
  if (target === 'prod') confirmProd.value = true
  else void app.setTarget('dev')
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = text
    setTimeout(() => (copied.value = null), 1500)
  } catch {
    app.toast(t('settings.copyFailed'), 'error')
  }
}

const shortcuts: [string, MessageKey][] = [
  ['/', 'settings.key.focusSearch'],
  ['Esc', 'settings.key.deselect'],
  ['[', 'shell.key.sidebar'],
  [']', 'shell.key.pane'],
  ['B', 'shell.pane.background'],
  ['F', 'shell.pane.map-data'],
  ['I', 'shell.pane.validation'],
  ['L', 'shell.pane.rules'],
  ['P', 'shell.pane.presets'],
  ['D', 'shell.pane.drafts'],
  ['↵', 'settings.key.approve'],
  ['E', 'settings.key.editTags'],
  ['S', 'settings.key.skip'],
  ['R', 'settings.key.reject'],
  ['O', 'settings.key.openId'],
  ['J / K', 'settings.key.nextPrev'],
  ['Space', 'settings.key.beforeAfter'],
  ['F', 'settings.key.zoom'],
  ['⌘ ↵', 'settings.key.saveTags'],
]
</script>

<template>
  <div class="flex h-full flex-col" data-testid="settings">
    <div class="scroll-thin min-h-0 flex-1 divide-y divide-line-strong overflow-y-auto bg-surface-2">
      <section class="space-y-2 px-4 py-4">
        <h3 class="section-title">{{ t('settings.account') }}</h3>
        <div class="flex items-center gap-3">
          <span class="grid size-8 place-items-center rounded-full border border-line-strong bg-surface text-fg-2"><Icon name="user" /></span>
          <div class="min-w-0 flex-1">
            <template v-if="app.account?.connected">
              <div class="font-bold">{{ app.account.displayName }}</div>
              <div class="text-[12px] text-fg-3">{{ app.target === 'prod' ? t('settings.connectedProd') : t('settings.connectedDev') }}</div>
            </template>
            <template v-else>
              <div class="font-bold">{{ t('settings.notConnected') }}</div>
              <div class="text-[12px] text-fg-3">
                {{ app.account?.configured === false ? t('settings.oauthMissing') : t('settings.connectHint') }}
              </div>
            </template>
          </div>
          <button v-if="app.account?.connected" class="btn" @click="app.logout()"><Icon name="logout" :size="13" /> {{ t('settings.disconnect') }}</button>
          <a v-else href="/auth/login" class="btn btn-primary" :class="app.account?.configured === false && 'pointer-events-none opacity-50'">{{ t('settings.connect') }}</a>
        </div>
      </section>

      <section class="flex items-center gap-3 px-4 py-4">
        <h3 class="section-title">{{ t('common.language') }}</h3>
        <div class="seg ml-auto" role="group" :aria-label="t('common.language')" data-testid="settings-locale">
          <button
            v-for="l in LOCALES"
            :key="l"
            class="seg-btn h-7 px-3"
            :aria-pressed="locale === l"
            :data-testid="`settings-locale-${l}`"
            @click="setLocale(l)"
          >
            {{ l === 'cs' ? t('common.lang.cs') : t('common.lang.en') }}
          </button>
        </div>
      </section>

      <section class="space-y-2 px-4 py-4">
        <h3 class="section-title">{{ t('settings.target') }}</h3>
        <div class="grid grid-cols-2 gap-2">
          <button
            class="min-w-0 rounded-md border p-3 text-left [overflow-wrap:anywhere] transition-colors"
            :class="app.target === 'dev' ? 'border-accent bg-accent-soft shadow-[inset_0_0_0_1px_var(--accent)]' : 'border-line-strong bg-surface hover:bg-surface-3'"
            @click="pickTarget('dev')"
          >
            <div class="font-bold">{{ t('settings.dev') }}</div>
            <div class="text-[12px] text-fg-3">{{ t('settings.devHint') }}</div>
          </button>
          <button
            class="min-w-0 rounded-md border p-3 text-left [overflow-wrap:anywhere] transition-colors"
            :class="app.target === 'prod' ? 'border-prod bg-prod-soft shadow-[inset_0_0_0_1px_var(--prod)]' : 'border-line-strong bg-surface hover:bg-surface-3'"
            @click="pickTarget('prod')"
          >
            <div class="flex items-center gap-1.5 font-bold" :class="app.target === 'prod' && 'text-prod'">
              <Icon name="alert" :size="13" /> {{ t('settings.prod') }}
            </div>
            <div class="text-[12px] text-fg-3">{{ t('settings.prodHint') }}</div>
          </button>
        </div>
      </section>

      <section class="space-y-2 px-4 py-4">
        <div class="flex items-center">
          <h3 class="section-title">{{ t('settings.imagery') }}</h3>
          <button v-if="!editingImagery" class="btn btn-ghost btn-sm ml-auto" data-testid="edit-imagery" @click="editingImagery = true">
            <Icon name="edit" :size="12" /> {{ t('settings.editSources') }}
          </button>
        </div>
        <ImageryEditor v-if="editingImagery" @close="editingImagery = false" />
        <div v-else class="card overflow-hidden">
          <label class="flex cursor-pointer items-center gap-2.5 border-b border-line px-3 py-1.5 transition-colors hover:bg-surface-3">
            <input type="radio" name="imagery" :checked="!mapStore.imageryId" @change="mapStore.setImagery(null)" />
            <span>{{ t('common.none') }}</span>
          </label>
          <label
            v-for="s in mapStore.allImagery"
            :key="s.id"
            class="flex cursor-pointer items-center gap-2.5 border-b border-line px-3 py-1.5 transition-colors last:border-b-0 hover:bg-surface-3"
          >
            <input type="radio" name="imagery" :checked="mapStore.imageryId === s.id" @change="mapStore.setImagery(s.id)" />
            <span class="min-w-0 flex-1 truncate">{{ s.name }}</span>
            <span class="chip bg-surface-3 font-mono text-fg-3 uppercase">{{ s.type }}</span>
            <span class="text-[11px] text-fg-3">z{{ s.maxZoom }}</span>
          </label>
        </div>
      </section>

      <section class="space-y-2 px-4 py-4">
        <h3 class="section-title">{{ t('settings.mirror') }}</h3>
        <div v-if="app.mirror" class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[12px]">
          <span class="text-fg-3">{{ t('settings.status') }}</span>
          <span class="flex items-center gap-1.5">
            <span class="size-2 rounded-full" :class="app.mirror.available ? 'bg-added' : 'bg-error'" />
            {{ app.mirror.available ? t('settings.available') : t('settings.unavailable') }}
          </span>
          <span class="text-fg-3">{{ t('settings.file') }}</span>
          <span class="truncate font-mono text-[12px]">{{ app.mirror.file ?? '—' }}</span>
          <span class="text-fg-3">{{ t('settings.dataAsOf') }}</span>
          <span :title="dateTime(app.mirror.dataTimestamp)">{{ app.mirror.dataTimestamp ? relativeTime(app.mirror.dataTimestamp) : '—' }}</span>
          <span class="text-fg-3">{{ t('settings.opened') }}</span>
          <span>{{ dateTime(app.mirror.openedAt) }}</span>
          <template v-if="app.mirror.message">
            <span class="text-fg-3">{{ t('settings.note') }}</span>
            <span class="text-fg-2">{{ app.mirror.message }}</span>
          </template>
        </div>
        <div v-if="app.mirror" class="pt-1" data-testid="sidecar-status">
          <div class="mb-1 text-[12px] font-bold text-fg">{{ t('settings.sidecar') }}</div>
          <div v-if="!sidecar" class="text-[12px] text-fg-3">{{ t('settings.noSidecar') }}</div>
          <div v-else class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[12px]">
            <span class="text-fg-3">{{ t('settings.lastRun') }}</span>
            <span class="flex flex-wrap items-center gap-1.5">
              <span class="size-2 rounded-full" :class="sidecarResultClass" />
              <span class="font-bold">{{ sidecar.result ?? t('common.unknown') }}</span>
              <span v-if="sidecar.lastRunKind" class="chip bg-surface-3 text-fg-2">{{ sidecar.lastRunKind }}</span>
              <span class="text-fg-2" :title="dateTime(sidecar.lastRunAt)">{{ sidecar.lastRunAt ? relativeTime(sidecar.lastRunAt) : '—' }}</span>
            </span>
            <template v-if="sidecar.error">
              <span class="text-fg-3">{{ t('common.error') }}</span>
              <span class="font-mono text-[11.5px] break-words text-error">{{ sidecar.error }}</span>
            </template>
            <span class="text-fg-3">{{ t('settings.lastFullBuild') }}</span>
            <span :title="dateTime(sidecar.lastFullBuildAt)">{{ sidecar.lastFullBuildAt ? relativeTime(sidecar.lastFullBuildAt) : '—' }}</span>
            <span class="text-fg-3">{{ t('settings.nextRebuild') }}</span>
            <span :title="dateTime(sidecar.nextFullBuildDue)">{{ sidecar.nextFullBuildDue ? relativeTime(sidecar.nextFullBuildDue) : '—' }}</span>
            <span class="text-fg-3">{{ t('settings.replication') }}</span>
            <span>
              <span class="font-mono text-[12px]">{{ sidecar.replicationSequence ?? '—' }}</span>
              <span v-if="sidecar.replicationTimestamp" class="text-fg-2" :title="dateTime(sidecar.replicationTimestamp)">
                · {{ relativeTime(sidecar.replicationTimestamp) }}</span
              >
            </span>
            <template v-if="sidecar.current">
              <span class="text-fg-3">{{ t('settings.published') }}</span>
              <span class="truncate font-mono text-[12px]" :title="sidecar.current">{{ sidecar.current }}</span>
            </template>
          </div>
        </div>
        <div v-else class="text-fg-3">{{ app.serverError ?? t('common.unknown') }}</div>
      </section>

      <section class="space-y-2 px-4 py-4">
        <h3 class="section-title">Claude (MCP)</h3>
        <p class="text-[12px] text-fg-2">{{ t('settings.mcpHint') }}</p>
        <div class="flex items-center gap-2">
          <code class="min-w-0 flex-1 truncate rounded-md border border-line-strong bg-surface px-2.5 py-1.5 font-mono text-[12px]">{{ mcpUrl }}</code>
          <button class="btn" @click="copy(mcpUrl)"><Icon :name="copied === mcpUrl ? 'check' : 'copy'" :size="13" /> {{ t('common.copy') }}</button>
        </div>
        <div v-if="mcp?.token" class="flex items-center gap-2">
          <code class="min-w-0 flex-1 truncate rounded-md border border-line-strong bg-surface px-2.5 py-1.5 font-mono text-[12px]" :title="t('settings.tokenTitle')">{{ maskedToken }}</code>
          <button class="btn btn-ghost btn-icon" :title="showToken ? t('settings.hideToken') : t('settings.showToken')" @click="showToken = !showToken">
            <Icon :name="showToken ? 'eye-off' : 'eye'" :size="14" />
          </button>
          <button class="btn" @click="copy(mcp.token)"><Icon :name="copied === mcp.token ? 'check' : 'copy'" :size="13" /> {{ t('settings.copyToken') }}</button>
        </div>
        <div class="space-y-1">
          <div class="text-[11.5px] text-fg-3">{{ t('settings.claudeCmd') }}</div>
          <div class="flex items-start gap-2">
            <code class="min-w-0 flex-1 rounded-md border border-line-strong bg-surface px-2.5 py-1.5 font-mono text-[12px] break-all">{{ shownCmd }}</code>
            <button class="btn" @click="copy(claudeCmd)"><Icon :name="copied === claudeCmd ? 'check' : 'copy'" :size="13" /> {{ t('common.copy') }}</button>
          </div>
        </div>
      </section>

      <section class="space-y-2 px-4 py-4">
        <h3 class="section-title">{{ t('settings.josm') }}</h3>
        <p class="text-[12px] text-fg-2">
          {{ t('settings.josmBefore') }}
          <code class="font-mono">{{ app.status?.remoteControlPort ?? 8111 }}</code>{{ t('settings.josmAfter') }}
        </p>
      </section>

      <section class="space-y-2 px-4 py-4">
        <h3 class="section-title">{{ t('settings.keyboard') }}</h3>
        <div class="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-x-6 gap-y-1 text-[12px]">
          <div v-for="[k, l] in shortcuts" :key="k" class="flex items-center justify-between gap-2">
            <span class="text-fg-2">{{ t(l) }}</span><span class="kbd">{{ k }}</span>
          </div>
        </div>
      </section>
    </div>
    <footer class="flex shrink-0 items-center border-t border-line-strong bg-surface-2 px-4 py-2.5">
      <span class="text-[11.5px] text-fg-3">OSM Review Editor {{ app.status?.version ?? '' }} · Apache-2.0</span>
    </footer>
  </div>

  <ConfirmDialog
    v-if="confirmProd"
    :title="t('settings.prodConfirmTitle')"
    :confirm-label="t('settings.prodConfirm')"
    danger
    @cancel="confirmProd = false"
    @confirm="(confirmProd = false), app.setTarget('prod', true)"
  >
    {{ t('settings.prodConfirmBody') }}
  </ConfirmDialog>
</template>
