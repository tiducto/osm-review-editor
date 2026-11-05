import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

const backend = process.env.ORE_BACKEND ?? 'http://localhost:3000'

const idEnvNames = [
  'ID_API_CONNECTION',
  'ID_API_CONNECTION_API_URL',
  'ID_API_CONNECTION_CLIENT_ID',
  'ID_API_CONNECTION_URL',
  'ID_NOMINATIM_API_URL',
  'ID_NSI_CDN_URL',
  'ID_OCI_CDN_URL',
  'ID_PRESETS_CDN_URL',
  'ID_SHOW_DONATION_MESSAGE',
  'ID_TAGINFO_API_URL',
  'ID_WMF_SITEMATRIX_CDN_URL',
]
const idDefines = Object.fromEntries(idEnvNames.map((name) => [`ENV__${name}`, 'null']))
const zoomTransformShim = fileURLToPath(new URL('./src/editor/id/shims/d3-zoom-transform.ts', import.meta.url))
const zoomTransformPattern = /^(\.\.\/)+node_modules\/d3-zoom\/src\/transform\.js$/
const idZoomTransform = {
  name: 'id-d3-zoom-transform',
  resolveId(source: string) {
    return zoomTransformPattern.test(source) ? zoomTransformShim : null
  },
}

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  define: idDefines,
  resolve: {
    alias: [
      { find: '@', replacement: fileURLToPath(new URL('./src', import.meta.url)) },
      { find: zoomTransformPattern, replacement: zoomTransformShim },
    ],
  },
  optimizeDeps: {
    rolldownOptions: { plugins: [idZoomTransform] },
  },
  server: {
    port: Number(process.env.ORE_WEB_PORT ?? 5173),
    strictPort: true,
    proxy: {
      '/api/events': { target: backend, ws: true, changeOrigin: true },
      '/api': { target: backend, changeOrigin: true },
      '/auth': { target: backend, changeOrigin: true },
      '/mcp': { target: backend, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 3000,
  },
  test: {
    server: { deps: { inline: [/@openstreetmap\/id/] } },
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    testTimeout: 60000,
  },
})
