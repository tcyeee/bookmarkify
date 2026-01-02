<template>
  <div
    class="w-app h-app rounded-[22%] bg-gray-100 center shadow overflow-hidden"
    :class="isDev ? 'border-4 border-dashed border-red-200' : ''">
    <div class="h-20 w-20 bg-white flex justify-center items-center" :style="logoStyle">
      <img v-if="props.value.iconHdUrl && !hdError" :src="props.value.iconHdUrl" alt="" @error="onHdError" />
      <img
        v-else-if="!iconError"
        :class="base64SizeClass"
        :src="`data:image/png;base64,${props.value.iconBase64}`"
        alt=""
        @error="onIconError" />
      <img v-else class="w-8 h-8" src="/avatar/default.png" alt="" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import type { Bookmark } from '@typing'

const props = defineProps<{  value: Bookmark}>()

const hdError = ref(false)
const iconError = ref(false)
const backgroundColor = ref('#ffffff')
const shouldUpscale = ref(false)
const isDev = computed(() => isLocalhostOrIP(props.value.urlFull))
const shouldUseBase64 = computed(
  () => (!props.value.iconHdUrl || hdError.value) && !iconError.value && !!props.value.iconBase64,
)
const logoStyle = computed(() =>
  shouldUseBase64.value ? { backgroundColor: backgroundColor.value } : undefined,
)
const base64SizeClass = computed(() => (shouldUpscale.value ? 'w-12 h-12' : 'w-8 h-8'))

function onHdError() {
  hdError.value = true
}

function onIconError() {
  iconError.value = true
}

watch(
  () => props.value.iconBase64,
  async (base64) => {
    if (!import.meta.client || !base64) {
      backgroundColor.value = '#ffffff'
      shouldUpscale.value = false
      return
    }

    try {
      const { color, upscale } = await analyzeBase64(base64)
      backgroundColor.value = color
      shouldUpscale.value = upscale
    } catch {
      backgroundColor.value = '#ffffff'
      shouldUpscale.value = false
      iconError.value = true
    }
  },
  { immediate: true },
)

async function analyzeBase64(base64: string): Promise<{ color: string; upscale: boolean }> {
  const img = await loadBase64Image(base64)
  const upscale = Math.max(img.width, img.height) >= 64
  const color = computeAverageColor(img)
  return { color, upscale }
}

function computeAverageColor(img: HTMLImageElement): string {
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')

  if (!ctx || !img.width || !img.height) {
    return '#ffffff'
  }

  const targetWidth = Math.min(img.width, 64)
  const targetHeight = Math.min(img.height, 64)
  canvas.width = targetWidth
  canvas.height = targetHeight

  ctx.drawImage(img, 0, 0, targetWidth, targetHeight)
  const { data } = ctx.getImageData(0, 0, targetWidth, targetHeight)

  let r = 0
  let g = 0
  let b = 0
  let count = 0

  // 取平均色，步长减少开销
  const step = 4 * 4
  for (let i = 0; i + 3 < data.length; i += step) {
    const alpha = data[i + 3] ?? 0
    if (!alpha) continue
    r += data[i] ?? 0
    g += data[i + 1] ?? 0
    b += data[i + 2] ?? 0
    count++
  }

  if (!count) return '#ffffff'

  return rgbToHex(Math.round(r / count), Math.round(g / count), Math.round(b / count))
}

function loadBase64Image(base64: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = `data:image/png;base64,${base64}`
  })
}

function rgbToHex(r: number, g: number, b: number): string {
  return `#${[r, g, b]
    .map((val) => {
      const hex = val.toString(16)
      return hex.length === 1 ? `0${hex}` : hex
    })
    .join('')}`
}

function isLocalhostOrIP(url: string): boolean {
  const localhostRegex = /^(localhost|127\.0\.0\.1|::1)$/i
  const ipRegex = /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/

  try {
    const hostname = new URL(url).hostname
    return localhostRegex.test(hostname) || ipRegex.test(hostname)
  } catch {
    return false
  }
}
</script>

