<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { McpInfo, OsmTarget } from '@/generated/models'
import { api } from '@/api/client'
import { useAppStore, type ThemePref } from '@/stores/app'
import { useMapStore } from '@/stores/map'
import { dateTime, relativeTime } from '@/lib/format'
import Modal from './Modal.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import Icon from './Icon.vue'

const app = useAppStore()
const mapStore = useMapStore()
const confirmProd = ref(false)
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

function pickTarget(t: OsmTarget) {
  if (t === app.target) return
  if (t === 'prod') confirmProd.value = true
  else void app.setTarget('dev')
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = text
    setTimeout(() => (copied.value = null), 1500)
  } catch {
    app.toast('Copy failed; select the text instead', 'error')
  }
}

const themes: { id: ThemePref; label: string }[] = [
  { id: 'system', label: 'System' },
  { id: 'light', label: 'Light' },
  { id: 'dark', label: 'Dark' },
]

const shortcuts = [
  ['/', 'Focus search'],
  ['Esc', 'Deselect / leave review'],
  ['[  ]', 'Toggle side panels'],
  ['↵', 'Approve change'],
  ['E', 'Edit tags'],
  ['S', 'Skip'],
  ['R', 'Reject with comment'],
  ['O', 'Open in iD'],
  ['J / K', 'Next / previous change'],
  ['Space', 'Before / after'],
  ['F', 'Zoom to change'],
  ['⌘ ↵', 'Save tag edit'],
]
</script>

<template>
  <Modal title="Settings" width="640px" @close="app.settingsOpen = false">
    <div class="divide-y divide-line">
      <section class="space-y-2 px-5 py-4">
        <h3 class="section-title">OpenStreetMap account</h3>
        <div class="flex items-center gap-3">
          <span class="grid size-8 place-items-center rounded-full bg-surface-3 text-fg-2"><Icon name="user" /></span>
          <div class="min-w-0 flex-1">
            <template v-if="app.account?.connected">
              <div class="font-medium">{{ app.account.displayName }}</div>
              <div class="text-[12px] text-fg-3">Connected to the {{ app.target === 'prod' ? 'production' : 'development' }} API</div>
            </template>
            <template v-else>
              <div class="font-medium">Not connected</div>
              <div class="text-[12px] text-fg-3">
                {{ app.account?.configured === false ? 'OAuth is not configured for this target on the server.' : 'Connect to upload approved changes.' }}
              </div>
            </template>
          </div>
          <button v-if="app.account?.connected" class="btn" @click="app.logout()"><Icon name="logout" :size="13" /> Disconnect</button>
          <a v-else href="/auth/login" class="btn btn-primary" :class="app.account?.configured === false && 'pointer-events-none opacity-50'">Connect OSM account</a>
        </div>
      </section>

      <section class="space-y-2 px-5 py-4">
        <h3 class="section-title">API target</h3>
        <div class="grid grid-cols-2 gap-2">
          <button
            class="rounded-lg border p-3 text-left transition-colors"
            :class="app.target === 'dev' ? 'border-accent bg-accent-soft' : 'border-line hover:bg-surface-2'"
            @click="pickTarget('dev')"
          >
            <div class="font-medium">Development</div>
            <div class="text-[12px] text-fg-3">master.apis.dev.openstreetmap.org: safe for testing</div>
          </button>
          <button
            class="rounded-lg border p-3 text-left transition-colors"
            :class="app.target === 'prod' ? 'border-prod bg-prod-soft' : 'border-line hover:bg-surface-2'"
            @click="pickTarget('prod')"
          >
            <div class="flex items-center gap-1.5 font-medium" :class="app.target === 'prod' && 'text-prod'">
              <Icon name="alert" :size="13" /> Production
            </div>
            <div class="text-[12px] text-fg-3">openstreetmap.org: real edits, visible to everyone</div>
          </button>
        </div>
      </section>

      <section class="space-y-2 px-5 py-4">
        <h3 class="section-title">Imagery</h3>
        <div class="overflow-hidden rounded-md border border-line">
          <label class="flex cursor-pointer items-center gap-2.5 border-b border-line px-3 py-1.5 hover:bg-surface-2">
            <input type="radio" name="imagery" :checked="!mapStore.imageryId" @change="mapStore.setImagery(null)" />
            <span>None</span>
          </label>
          <label
            v-for="s in mapStore.allImagery"
            :key="s.id"
            class="flex cursor-pointer items-center gap-2.5 border-b border-line px-3 py-1.5 last:border-b-0 hover:bg-surface-2"
          >
            <input type="radio" name="imagery" :checked="mapStore.imageryId === s.id" @change="mapStore.setImagery(s.id)" />
            <span class="min-w-0 flex-1 truncate">{{ s.name }}</span>
            <span class="chip bg-surface-3 font-mono text-fg-3 uppercase">{{ s.type }}</span>
            <span class="text-[11px] text-fg-3">z{{ s.maxZoom }}</span>
          </label>
        </div>
      </section>

      <section class="space-y-2 px-5 py-4">
        <h3 class="section-title">Local mirror</h3>
        <div v-if="app.mirror" class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[12.5px]">
          <span class="text-fg-3">Status</span>
          <span class="flex items-center gap-1.5">
            <span class="size-2 rounded-full" :class="app.mirror.available ? 'bg-added' : 'bg-error'" />
            {{ app.mirror.available ? 'Available' : 'Unavailable' }}
          </span>
          <span class="text-fg-3">File</span>
          <span class="truncate font-mono text-[12px]">{{ app.mirror.file ?? '—' }}</span>
          <span class="text-fg-3">Data as of</span>
          <span :title="dateTime(app.mirror.dataTimestamp)">{{ app.mirror.dataTimestamp ? relativeTime(app.mirror.dataTimestamp) : '—' }}</span>
          <span class="text-fg-3">Opened</span>
          <span>{{ dateTime(app.mirror.openedAt) }}</span>
          <template v-if="app.mirror.message">
            <span class="text-fg-3">Note</span>
            <span class="text-fg-2">{{ app.mirror.message }}</span>
          </template>
        </div>
        <div v-else class="text-fg-3">{{ app.serverError ?? 'Unknown' }}</div>
      </section>

      <section class="space-y-2 px-5 py-4">
        <h3 class="section-title">Claude (MCP)</h3>
        <p class="text-[12.5px] text-fg-2">Add this server to Claude so it can find issues and propose drafts you review here.</p>
        <div class="flex items-center gap-2">
          <code class="min-w-0 flex-1 truncate rounded-md border border-line bg-surface-2 px-2.5 py-1.5 font-mono text-[12px]">{{ mcpUrl }}</code>
          <button class="btn" @click="copy(mcpUrl)"><Icon :name="copied === mcpUrl ? 'check' : 'copy'" :size="13" /> Copy</button>
        </div>
        <div v-if="mcp?.token" class="flex items-center gap-2">
          <code class="min-w-0 flex-1 truncate rounded-md border border-line bg-surface-2 px-2.5 py-1.5 font-mono text-[12px]" title="Bearer token for the MCP endpoint">{{ maskedToken }}</code>
          <button class="btn btn-ghost btn-icon" :title="showToken ? 'Hide token' : 'Show token'" @click="showToken = !showToken">
            <Icon :name="showToken ? 'eye-off' : 'eye'" :size="14" />
          </button>
          <button class="btn" @click="copy(mcp.token)"><Icon :name="copied === mcp.token ? 'check' : 'copy'" :size="13" /> Copy token</button>
        </div>
        <div class="space-y-1">
          <div class="text-[11.5px] text-fg-3">Claude Code command</div>
          <div class="flex items-start gap-2">
            <code class="min-w-0 flex-1 rounded-md border border-line bg-surface-2 px-2.5 py-1.5 font-mono text-[12px] break-all">{{ claudeCmd }}</code>
            <button class="btn" @click="copy(claudeCmd)"><Icon :name="copied === claudeCmd ? 'check' : 'copy'" :size="13" /> Copy</button>
          </div>
        </div>
      </section>

      <section class="space-y-2 px-5 py-4">
        <h3 class="section-title">JOSM remote control</h3>
        <p class="text-[12.5px] text-fg-2">
          QA tools that "open in JOSM" talk to this app on port
          <code class="font-mono">{{ app.status?.remoteControlPort ?? 8111 }}</code>. Real JOSM uses the same port, so it cannot run at the same
          time as this tool.
        </p>
      </section>

      <section class="space-y-2 px-5 py-4">
        <h3 class="section-title">Appearance</h3>
        <div class="flex w-fit rounded-md bg-surface-3 p-0.5">
          <button
            v-for="t in themes"
            :key="t.id"
            class="rounded px-3 py-1 text-[12px] font-medium"
            :class="app.themePref === t.id ? 'bg-surface text-fg shadow-sm' : 'text-fg-3 hover:text-fg-2'"
            @click="app.setTheme(t.id)"
          >
            {{ t.label }}
          </button>
        </div>
      </section>

      <section class="space-y-2 px-5 py-4">
        <h3 class="section-title">Keyboard</h3>
        <div class="grid grid-cols-2 gap-x-6 gap-y-1 text-[12.5px]">
          <div v-for="[k, l] in shortcuts" :key="k" class="flex items-center justify-between gap-2">
            <span class="text-fg-2">{{ l }}</span><span class="kbd">{{ k }}</span>
          </div>
        </div>
      </section>
    </div>
    <template #footer>
      <span class="text-[11.5px] text-fg-3">OSM Review Editor {{ app.status?.version ?? '' }} · Apache-2.0</span>
    </template>
  </Modal>

  <ConfirmDialog
    v-if="confirmProd"
    title="Switch to production?"
    confirm-label="Use production"
    danger
    @cancel="confirmProd = false"
    @confirm="(confirmProd = false), app.setTarget('prod', true)"
  >
    Uploads will go to the live OpenStreetMap database at openstreetmap.org. Every merged change becomes a real, public edit under your
    account. You will need to connect your production OSM account.
  </ConfirmDialog>
</template>
