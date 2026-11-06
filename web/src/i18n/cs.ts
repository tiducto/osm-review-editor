import type { en } from './en'
import type { Translation } from './types'
import { commonCs } from './messages/common'
import { draftsCs } from './messages/drafts'
import { editorCs } from './messages/editor'
import { mapCs } from './messages/map'
import { presetsCs } from './messages/presets'
import { remoteCs } from './messages/remote'
import { reviewCs } from './messages/review'
import { rulesCs } from './messages/rules'
import { settingsCs } from './messages/settings'
import { shellCs } from './messages/shell'
import { topCs } from './messages/top'
import { validateCs } from './messages/validate'

export type Messages = Translation<typeof en>

export const cs: Messages = {
  ...commonCs,
  ...topCs,
  ...settingsCs,
  ...mapCs,
  ...validateCs,
  ...rulesCs,
  ...draftsCs,
  ...reviewCs,
  ...editorCs,
  ...presetsCs,
  ...remoteCs,
  ...shellCs,
}
