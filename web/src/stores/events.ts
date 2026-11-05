import type { UiEvent, UiEventRemoteCommand } from '@/generated/models'
import { EventStream } from '@/api/events'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useRulesStore } from './rules'
import { useDraftsStore } from './drafts'
import { useHighlightsStore } from './highlights'
import { useRuleFilesStore } from './ruleFiles'
import { useScansStore } from './scans'
import { usePushView } from './pushView'
import { usePresetsStore } from './presets'

export function connectEvents(): () => void {
  const app = useAppStore()
  const mapStore = useMapStore()
  const rules = useRulesStore()
  const drafts = useDraftsStore()
  const highlights = useHighlightsStore()
  const ruleFiles = useRuleFilesStore()
  const scans = useScansStore()
  const pushView = usePushView()
  const presets = usePresetsStore()

  function remote(cmd: UiEventRemoteCommand) {
    if (cmd.imagery) {
      mapStore.addImagery(cmd.imagery)
      app.toast(`Imagery added: ${cmd.imagery.name}`)
    }
    if (cmd.message) app.toast(cmd.message, 'info', 9000)
    const ids = cmd.draftIds.length ? cmd.draftIds : cmd.draftId ? [cmd.draftId] : []
    if (ids.length === 1) {
      void drafts.open(ids[0], { review: true })
      window.focus()
      return
    }
    if (ids.length > 1) {
      app.toast(`Remote control created ${ids.length} drafts`, 'success')
      drafts.statusFilter = 'open'
      drafts.drawerTab = 'drafts'
      drafts.drawerOpen = true
      void drafts.loadDrafts()
    }
    if (drafts.reviewing) drafts.stopReview()
    if (cmd.bbox) mapStore.fitBBox(cmd.bbox, 19)
    if (cmd.select.length) {
      mapStore.highlighted = cmd.select.length > 1 ? cmd.select : []
      void mapStore.select(cmd.select[0], { fly: !cmd.bbox })
    }
    window.focus()
  }

  function handle(e: UiEvent) {
    switch (e.type) {
      case 'remote_command':
        remote(e)
        break
      case 'draft_updated':
        void drafts.onDraftUpdated(e.draftId)
        break
      case 'merge_progress':
        drafts.onMergeProgress(e.progress)
        break
      case 'mirror_swapped':
        app.setMirror(e.status)
        void rules.refresh()
        mapStore.refreshTiles()
        app.toast('Local mirror updated')
        void highlights.load()
        break
      case 'rules_changed':
        void highlights.load()
        void ruleFiles.onRulesChanged(e.changed, e.removed)
        void presets.onRulesChanged([...e.changed, ...e.removed])
        break
      case 'scan_finished':
        void scans.onScanFinished(e)
        break
      case 'push_view':
        void pushView.apply(e)
        break
    }
  }

  let wasOpen = false
  const stream = new EventStream(handle, (s) => {
    app.connection = s
    if (s === 'open' && wasOpen) {
      void app.loadStatus()
      void drafts.loadDrafts()
    }
    if (s === 'open') wasOpen = true
  })
  stream.start()
  return () => stream.stop()
}
