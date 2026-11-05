import type { UiEvent, UiEventRemoteCommand } from '@/generated/models'
import { EventStream } from '@/api/events'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useRulesStore } from './rules'
import { useDraftsStore } from './drafts'

export function connectEvents(): () => void {
  const app = useAppStore()
  const mapStore = useMapStore()
  const rules = useRulesStore()
  const drafts = useDraftsStore()

  function remote(cmd: UiEventRemoteCommand) {
    if (cmd.imagery) {
      mapStore.addImagery(cmd.imagery)
      app.toast(`Imagery added: ${cmd.imagery.name}`)
    }
    if (cmd.draftId) {
      void drafts.open(cmd.draftId, { review: true })
      return
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
        void mapStore.loadFeatures()
        app.toast('Local mirror updated')
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
