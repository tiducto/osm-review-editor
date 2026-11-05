<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import ComboInput from './ComboInput.vue'
import MixedHint from './MixedHint.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import {
  formatNumber,
  optionTitle,
  readRoadheight,
  readRoadspeed,
  ROADHEIGHT_SPECIAL,
  ROADSPEED_SPECIAL,
  roadspeedSuggestions,
  writeRoadheight,
  writeRoadspeed,
  type ComboOption,
  type HeightUnit,
  type SpeedUnit,
} from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const combo = ref<InstanceType<typeof ComboInput> | null>(null)
const isSpeed = computed(() => props.field.type === 'roadspeed')
const speed = computed(() => readRoadspeed(props.field, props.tags, props.locale))
const height = computed(() => readRoadheight(props.field, props.tags, props.locale))
const speedUnit = ref<SpeedUnit>(speed.value.unit)
const heightUnit = ref<HeightUnit>(height.value.unit)

watch(
  () => [speed.value.unit, speed.value.value] as const,
  ([u, v]) => {
    if (v) speedUnit.value = u
  },
)
watch(
  () => [height.value.unit, height.value.primary] as const,
  ([u, v]) => {
    if (v) heightUnit.value = u
  },
)

function specialTitle(kind: 'speed' | 'height', v: string): string {
  return optionTitle(props.field, v) ?? tr(props.locale, `field.${kind}.${v}`)
}

const speedOptions = computed<ComboOption[]>(() => [
  ...roadspeedSuggestions(speedUnit.value).map((n) => ({ value: String(n), title: formatNumber(props.locale, n) })),
  ...ROADSPEED_SPECIAL.map((v) => ({ value: v, title: specialTitle('speed', v) })),
])
const heightOptions = computed<ComboOption[]>(() => ROADHEIGHT_SPECIAL.map((v) => ({ value: v, title: specialTitle('height', v) })))

const speedDisplay = computed(() => (speed.value.special ? specialTitle('speed', speed.value.special) : speed.value.value))
const heightDisplay = computed(() => (height.value.special ? specialTitle('height', height.value.special) : height.value.primary))
const mixed = computed(() => (isSpeed.value ? speed.value.mixed : height.value.mixed))
const placeholder = computed(() => (mixed.value ? tr(props.locale, 'field.multiple') : (props.field.placeholder ?? '')))

function send(patch: TagPatch) {
  if (Object.keys(patch).length) emit('change', patch)
}

function commitSpeed(text: string, option: ComboOption | null) {
  if (props.readonly) return
  send(writeRoadspeed(props.field, props.tags, option ? option.value : text, speedUnit.value))
}

function setSpeedUnit(e: Event) {
  speedUnit.value = (e.target as HTMLSelectElement).value as SpeedUnit
  if (speed.value.value && !speed.value.special) send(writeRoadspeed(props.field, props.tags, speed.value.value, speedUnit.value))
}

const secondaryEl = ref<HTMLInputElement | null>(null)

function commitHeight(text: string, option: ComboOption | null) {
  if (props.readonly) return
  const secondary = heightUnit.value === 'ft' ? (secondaryEl.value?.value ?? height.value.secondary) : ''
  send(writeRoadheight(props.field, props.tags, option ? option.value : text, secondary, heightUnit.value))
}

function commitSecondary(e: Event) {
  if (props.readonly) return
  const el = e.target as HTMLInputElement
  send(writeRoadheight(props.field, props.tags, height.value.special ? '' : height.value.primary, el.value, heightUnit.value))
  void nextTick(() => {
    el.value = height.value.secondary
  })
}

function setHeightUnit(e: Event) {
  heightUnit.value = (e.target as HTMLSelectElement).value as HeightUnit
  if (height.value.primary && !height.value.special) send(writeRoadheight(props.field, props.tags, height.value.primary, heightUnit.value === 'ft' ? height.value.secondary : '', heightUnit.value))
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
}

defineExpose({ focus: () => combo.value?.focus() })
</script>

<template>
  <div class="min-w-0">
    <div v-if="isSpeed" class="flex min-w-0">
      <div class="min-w-0 flex-1 [&_.input]:rounded-br-none">
        <ComboInput
          ref="combo"
          :model-value="speedDisplay"
          :options="speedOptions"
          :placeholder="placeholder"
          :title="mixed?.join('\n')"
          :readonly="readonly"
          @commit="commitSpeed"
        />
      </div>
      <select class="input -ml-px w-[4.5rem] shrink-0 rounded-bl-none px-1.5" :value="speedUnit" :disabled="readonly" :title="tr(locale, 'field.unit')" @change="setSpeedUnit">
        <option value="km/h">km/h</option>
        <option value="mph">mph</option>
      </select>
    </div>
    <div v-else class="flex min-w-0">
      <div class="min-w-0 flex-1 [&_.input]:rounded-br-none">
        <ComboInput
          ref="combo"
          :model-value="heightDisplay"
          :options="heightOptions"
          :placeholder="placeholder"
          :title="mixed?.join('\n')"
          :readonly="readonly"
          @commit="commitHeight"
        />
      </div>
      <input
        v-if="heightUnit === 'ft'"
        ref="secondaryEl"
        class="input -ml-px w-14 shrink-0 rounded-b-none"
        inputmode="decimal"
        :value="height.secondary"
        placeholder="in"
        :readonly="readonly"
        @change="commitSecondary"
        @keydown="onKey"
      />
      <select class="input -ml-px w-14 shrink-0 rounded-bl-none px-1.5" :value="heightUnit" :disabled="readonly" :title="tr(locale, 'field.unit')" @change="setHeightUnit">
        <option value="m">m</option>
        <option value="ft">ft</option>
      </select>
    </div>
    <MixedHint :values="mixed" />
  </div>
</template>
