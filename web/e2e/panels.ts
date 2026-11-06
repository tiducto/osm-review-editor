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
  draftRow: (page: Page, id: string): Locator => page.getByTestId(`draft-${id}`),
  draftRowReview: (page: Page, id: string): Locator => page.getByTestId(`draft-${id}`).getByRole('button', { name: 'Review' }),
  reviewRef: (page: Page): Locator => page.getByTestId('review-ref'),
  reviewApprove: (page: Page): Locator => page.getByTestId('review-approve'),
  reviewSkip: (page: Page): Locator => page.getByTestId('review-skip'),
  cascadeMessage: (page: Page): Locator => page.getByTestId('cascade-message'),
  cascadeRefs: (page: Page): Locator => page.getByTestId('cascade-refs'),
  dialog: (page: Page): Locator => page.getByRole('dialog'),
  remoteConfirm: (page: Page): Locator => page.getByTestId('remote-confirm'),
  remoteUrl: (page: Page): Locator => page.getByTestId('remote-url'),
  remoteHost: (page: Page): Locator => page.getByTestId('remote-host'),
  remoteTags: (page: Page): Locator => page.getByTestId('remote-tags'),
  remoteLoad: (page: Page): Locator => page.getByTestId('remote-load'),
  remoteCancel: (page: Page): Locator => page.getByTestId('remote-cancel'),
  editTool: (page: Page, mode: 'add-point' | 'add-line' | 'add-area'): Locator => page.getByTestId(`edit-${mode}`),
  editEnter: (page: Page): Locator => page.getByTestId('edit-enter'),
  editExit: (page: Page): Locator => page.getByTestId('edit-exit'),
  editCount: (page: Page): Locator => page.getByTestId('edit-count'),
  editUndo: (page: Page): Locator => page.getByTestId('edit-undo'),
}
