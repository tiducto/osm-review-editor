import type { Locator, Page } from '@playwright/test'

export type PaneName = 'background' | 'map-data' | 'validation' | 'rules' | 'presets' | 'drafts' | 'settings'

export const PANES: PaneName[] = ['background', 'map-data', 'validation', 'rules', 'presets', 'drafts', 'settings']

export const panels = {
  map: (page: Page): Locator => page.getByTestId('map'),
  sidebar: (page: Page): Locator => page.getByTestId('sidebar-left'),
  sidebarToggle: (page: Page): Locator => page.getByTestId('sidebar-toggle'),
  pane: (page: Page, name: PaneName): Locator => page.getByTestId(`pane-${name}`),
  paneButton: (page: Page, name: PaneName): Locator => page.getByTestId(`pane-button-${name}`),
  paneClose: (page: Page): Locator => page.getByTestId('pane-close'),
  sheet: (page: Page): Locator => page.getByTestId('bottom-sheet'),
  sheetToggle: (page: Page): Locator => page.getByTestId('sheet-toggle'),
  sheetTab: (page: Page, tab: 'left' | 'pane'): Locator => page.getByTestId(`sheet-tab-${tab}`),
  draftReview: (page: Page): Locator => page.getByTestId('pane-drafts').getByRole('button', { name: 'Review' }).first(),
  leaveReview: (page: Page): Locator => page.getByTitle('Leave review (Esc)'),
  imageryOptions: (page: Page): Locator => page.getByTestId('imagery-list').locator('input[type="radio"]'),
}
