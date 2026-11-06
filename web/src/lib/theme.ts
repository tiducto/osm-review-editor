import { computed, ref, watchEffect } from 'vue'

export type ThemePref = 'system' | 'light' | 'dark'

export const THEME_PREFS: ThemePref[] = ['system', 'light', 'dark']

const KEY = 'ore.theme'

function read(): ThemePref {
  try {
    const v = localStorage.getItem(KEY)
    if (v === 'system' || v === 'light' || v === 'dark') return v
  } catch {}
  return 'system'
}

const query = typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: dark)') : null

export const themePref = ref<ThemePref>(read())
const systemDark = ref(query?.matches ?? false)
query?.addEventListener('change', (e) => (systemDark.value = e.matches))

export const isDark = computed(() => themePref.value === 'dark' || (themePref.value === 'system' && systemDark.value))

export function setThemePref(p: ThemePref) {
  themePref.value = p
  try {
    localStorage.setItem(KEY, p)
  } catch {}
}

export function applyTheme() {
  watchEffect(() => {
    document.documentElement.classList.toggle('dark', isDark.value)
  })
}
