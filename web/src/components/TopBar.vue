<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAppStore } from '@/stores/app'
import { useDraftsStore } from '@/stores/drafts'
import AreaPicker from './AreaPicker.vue'
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
  <header class="topbar relative z-30 flex h-12 shrink-0 items-center gap-1.5 overflow-visible px-2 text-white sm:gap-2.5 sm:px-2.5">
    <div class="hidden items-center gap-2 pr-1 sm:flex">
      <img src="/favicon.svg" alt="" class="size-6" />
      <span class="hidden text-[14px] font-bold whitespace-nowrap xl:inline">OSM Review</span>
    </div>

    <AreaPicker />
    <SearchBox ref="search" />

    <div class="ml-auto flex min-w-0 items-center gap-1 sm:gap-2">
      <div v-if="draft" class="bar-group hidden min-w-0 overflow-hidden lg:flex">
        <button class="bar-btn min-w-0 justify-start gap-2 font-normal" :title="t('top.showQueue')" @click="openDraftChanges">
          <AuthorBadge v-if="parseAuthor(draft.author).kind === 'claude'" :author="draft.author" />
          <span class="max-w-60 min-w-0 truncate font-bold" :title="draft.title">{{ draft.title }}</span>
          <span class="chip bg-surface-3 font-mono text-fg-2" :title="t('top.approvedOf', { approved: draft.counts.approved, total: draft.counts.total })">
            {{ draft.counts.approved }}/{{ draft.counts.total }}
          </span>
        </button>
        <button
          v-if="!drafts.reviewing && draft.counts.total"
          class="bar-btn"
          :title="t('top.reviewDraft')"
          @click="drafts.startReview()"
        >
          {{ t('top.review') }}
        </button>
        <button class="bar-btn px-2" :title="t('top.closeDraft')" @click="drafts.close()"><Icon name="x" :size="14" /></button>
      </div>
      <button class="bar-btn" :class="canMerge && 'bg-accent text-white hover:bg-accent-strong'" :disabled="!canMerge" :title="t('top.mergeTitle')" @click="draft && (app.mergeDraftId = draft.id)">
        <Icon name="merge" :size="16" /> <span class="hidden md:inline">{{ t('top.merge') }}</span>
      </button>

      <div class="mx-0.5 hidden h-6 w-px bg-white/30 md:block" />

      <button
        class="hidden h-5 cursor-pointer items-center gap-1 rounded-sm px-1.5 text-[11px] leading-none font-bold uppercase sm:inline-flex"
        :class="app.target === 'prod' ? 'bg-[#dd2222] text-white' : 'bg-black/30 text-white'"
        :title="app.target === 'prod' ? t('top.targetProdTitle') : t('top.targetDevTitle')"
        @click="app.settingsOpen = true"
      >
        <Icon v-if="app.target === 'prod'" name="alert" :size="12" />
        {{ app.target === 'prod' ? t('top.prod') : t('top.dev') }}
      </button>

      <a
        v-if="app.account && !app.account.connected"
        href="/auth/login"
        class="bar-btn text-fg hover:text-fg"
        :class="!app.account.configured && 'pointer-events-none opacity-50'"
        :title="app.account.configured ? t('top.signIn') : t('top.oauthMissing')"
      >
        <Icon name="user" :size="16" /> <span class="hidden lg:inline">{{ t('top.connect') }}</span>
      </a>
      <button v-else-if="app.account" class="bar-btn" :title="t('top.accountSettings')" @click="app.settingsOpen = true">
        <span class="grid size-5 place-items-center rounded-sm bg-accent text-[10px] font-bold text-accent-fg uppercase">
          {{ app.account.displayName?.charAt(0) ?? '?' }}
        </span>
        <span class="hidden max-w-32 truncate lg:inline">{{ app.account.displayName }}</span>
      </button>

      <div class="bar-group text-[11px] uppercase max-sm:hidden" role="group" :aria-label="t('common.switchLanguage')">
        <button
          v-for="l in LOCALES"
          :key="l"
          class="bar-btn h-8 min-w-0 px-2 text-[11px]"
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
        class="size-2.5 rounded-full ring-2 ring-white/70"
        :class="{ 'bg-[#22cc22]': app.connection === 'open', 'animate-pulse bg-warning': app.connection === 'connecting', 'bg-error': app.connection === 'closed' }"
        :title="connectionTitle"
      />
      <button class="bar-btn px-1.5" :title="t('top.settings')" @click="app.settingsOpen = true"><Icon name="settings" :size="18" /></button>
    </div>
  </header>
</template>
