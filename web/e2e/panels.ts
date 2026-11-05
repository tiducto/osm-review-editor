import type { Locator, Page } from '@playwright/test'

export const panels = {
  map: (page: Page): Locator => page.getByTestId('map'),
  leftPanel: (page: Page): Locator => page.getByTestId('left-panel'),
  leftToggle: (page: Page): Locator => page.getByTestId('left-toggle'),
  rightPanel: (page: Page): Locator => page.getByTestId('right-panel'),
  rightToggle: (page: Page): Locator => page.getByTestId('right-toggle'),
  sheetToggle: (page: Page): Locator => page.getByTestId('sheet-toggle'),
  bottomPanel: (page: Page): Locator => page.getByTestId('bottom-panel'),
  bottomCollapse: (page: Page): Locator => page.getByTestId('bottom-panel').getByTitle('Collapse'),
  bottomExpand: (page: Page): Locator => page.getByTestId('bottom-panel').getByTitle('Expand'),
  draftReview: (page: Page): Locator => page.getByTestId('bottom-panel').getByRole('button', { name: 'Review' }).first(),
  tab: (page: Page, name: 'map' | 'validate' | 'rules' | 'presets'): Locator => page.getByTestId(`tab-${name}`),
  leaveReview: (page: Page): Locator => page.getByTitle('Leave review (Esc)'),
}
