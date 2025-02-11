import FingerprintJS from '@fingerprintjs/fingerprintjs'
import { defineNuxtPlugin } from '#app'

export default defineNuxtPlugin(async (nuxtApp) => {
  if (!import.meta.client) return

  const fp = await FingerprintJS.load()
  const result = await fp.get()
  useState('userFingerprint', () => result.visitorId)
})