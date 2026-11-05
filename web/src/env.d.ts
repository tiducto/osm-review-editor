/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BASEMAP_LIGHT?: string
  readonly VITE_BASEMAP_DARK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
