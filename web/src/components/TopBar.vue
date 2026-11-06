<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAppStore } from '@/stores/app'
import { useDraftsStore } from '@/stores/drafts'
import AreaPicker from './AreaPicker.vue'
import MirrorIndicator from './MirrorIndicator.vue'
import SearchBox from './SearchBox.vue'
import AuthorBadge from './AuthorBadge.vue'
import Icon from './Icon.vue'
import { parseAuthor } from '@/lib/authors'
import { LOCALES, useI18n } from '@/i18n'

const { t, locale, setLocale } = useI18n()
const app = useAppStore()
const drafts = useDraftsStore()
const search = ref<InstanceType<typeof SearchBox> | null>(null)

const draft = computed(() => drafts.current?.draft ?? null)
const canMerge = computed(() => !!draft.value && draft.value.counts.approved > 0 && draft.value.status !== 'merged')
const connectionTitle = computed(
  () => ({ open: t('top.live.open'), connecting: t('top.live.connecting'), closed: t('top.live.closed') })[app.connection],
)

function openDraftChanges() {
  drafts.drawerOpen = true
  drafts.drawerTab = 'changes'
}

defineExpose({ focusSearch: () => search.value?.focus() })
</script>

<template>
  <header class="relative z-30 flex h-12 shrink-0 items-center gap-2 overflow-visible border-b border-line bg-surface px-2 sm:gap-3 sm:px-3" data-testid="top-bar">
    <div class="hidden items-center gap-2 pr-1 sm:flex">
      <img src="/favicon.svg" alt="" class="size-6" />
      <span class="hidden text-[13.5px] font-semibold tracking-tight whitespace-nowrap xl:inline">OSM Review</span>
    </div>

    <AreaPicker />
    <SearchBox ref="search" />

    <div class="ml-auto flex min-w-0 items-center gap-1.5 sm:gap-2">
      <MirrorIndicator />
      <div v-if="draft" class="hidden min-w-0 items-center gap-1 overflow-hidden lg:flex rounded-lg border border-line bg-surface-2 py-0.5 pr-0.5 pl-2.5">
        <button class="flex min-w-0 items-center gap-2 text-left" :title="t('top.showQueue')" @click="openDraftChanges">
          <AuthorBadge v-if="parseAuthor(draft.author).kind === 'claude'" :author="draft.author" />
          <span class="max-w-60 min-w-0 truncate font-medium" :title="draft.title">{{ draft.title }}</span>
          <span class="chip bg-surface-3 font-mono text-fg-2" :title="t('top.approvedOf', { approved: draft.counts.approved, total: draft.counts.total })">
            {{ draft.counts.approved }}/{{ draft.counts.total }}
          </span>
        </button>
        <button
          v-if="!drafts.reviewing && draft.counts.total"
          class="btn btn-ghost btn-sm"
          :title="t('top.reviewDraft')"
          @click="drafts.startReview()"
        >
          {{ t('top.review') }}
        </button>
        <button class="btn btn-ghost btn-icon btn-sm w-6" :title="t('top.closeDraft')" @click="drafts.close()"><Icon name="x" :size="13" /></button>
      </div>
      <button class="btn btn-primary h-8" :disabled="!canMerge" :title="t('top.mergeTitle')" @click="draft && (app.mergeDraftId = draft.id)">
        <Icon name="merge" :size="14" /> <span class="hidden md:inline">{{ t('top.merge') }}</span>
      </button>

      <div class="mx-1 hidden h-6 w-px bg-line md:block" />

      <button
        class="chip hidden h-6 cursor-pointer font-semibold tracking-wide uppercase sm:inline-flex"
        :class="app.target === 'prod' ? 'bg-prod-soft text-prod ring-1 ring-prod/40' : 'bg-surface-3 text-fg-2'"
        :title="app.target === 'prod' ? t('top.targetProdTitle') : t('top.targetDevTitle')"
        @click="app.settingsOpen = true"
      >
        <Icon v-if="app.target === 'prod'" name="alert" :size="12" :stroke="2.2" />
        {{ app.target === 'prod' ? t('top.prod') : t('top.dev') }}
      </button>

      <a
        v-if="app.account && !app.account.connected"
        href="/auth/login"
        class="btn h-8"
        :class="!app.account.configured && 'pointer-events-none opacity-50'"
        :title="app.account.configured ? t('top.signIn') : t('top.oauthMissing')"
      >
        <Icon name="user" :size="14" /> <span class="hidden lg:inline">{{ t('top.connect') }}</span>
      </a>
      <button v-else-if="app.account" class="btn btn-ghost h-8" :title="t('top.accountSettings')" @click="app.settingsOpen = true">
        <span class="grid size-5 place-items-center rounded-full bg-accent text-[10px] font-bold text-accent-fg uppercase">
          {{ app.account.displayName?.charAt(0) ?? '?' }}
        </span>
        <span class="hidden max-w-32 truncate lg:inline">{{ app.account.displayName }}</span>
      </button>

      <div class="flex shrink-0 overflow-hidden rounded-md border border-line text-[10.5px] font-semibold uppercase" role="group" :aria-label="t('common.switchLanguage')">
        <button
          v-for="l in LOCALES"
          :key="l"
          class="h-6 px-1.5 transition-colors"
          :class="locale === l ? 'bg-accent text-white' : 'text-fg-3 hover:bg-surface-2 hover:text-fg-2'"
          :title="t('common.switchLanguage')"
          :aria-label="`${t('common.switchLanguage')}: ${t(l === 'cs' ? 'common.lang.cs' : 'common.lang.en')}`"
          :aria-pressed="locale === l"
          :data-testid="`locale-${l}`"
          @click="setLocale(l)"
        >
          {{ l }}
        </button>
      </div>

      <span
        class="size-2 rounded-full"
        :class="{ 'bg-added': app.connection === 'open', 'animate-pulse bg-warning': app.connection === 'connecting', 'bg-error': app.connection === 'closed' }"
        :title="connectionTitle"
      />
      <button class="btn btn-ghost btn-icon" :title="t('top.settings')" @click="app.settingsOpen = true"><Icon name="settings" /></button>
    </div>
  </header>
</template>
