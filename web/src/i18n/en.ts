import { commonEn } from './messages/common'
import { draftsEn } from './messages/drafts'
import { editorEn } from './messages/editor'
import { mapEn } from './messages/map'
import { presetsEn } from './messages/presets'
import { remoteEn } from './messages/remote'
import { reviewEn } from './messages/review'
import { rulesEn } from './messages/rules'
import { settingsEn } from './messages/settings'
import { shellEn } from './messages/shell'
import { topEn } from './messages/top'
import { validateEn } from './messages/validate'

export const en = {
  ...commonEn,
  ...topEn,
  ...settingsEn,
  ...mapEn,
  ...validateEn,
  ...rulesEn,
  ...draftsEn,
  ...reviewEn,
  ...editorEn,
  ...presetsEn,
  ...remoteEn,
  ...shellEn,
}
