export const BASEMAP_LIGHT = import.meta.env.VITE_BASEMAP_LIGHT || 'https://tiles.openfreemap.org/styles/positron'
export const BASEMAP_DARK = import.meta.env.VITE_BASEMAP_DARK || 'https://tiles.openfreemap.org/styles/dark'

export function basemapUrl(dark: boolean): string {
  return dark ? BASEMAP_DARK : BASEMAP_LIGHT
}
