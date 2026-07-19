import { computed, readonly, ref } from 'vue'
import { AnalyticsClient, SESSION_KEY, VISITOR_KEY, type AnalyticsTrackInput } from '@/services/analyticsClient'

export const CONSENT_KEY = 'portfolio.analytics.consent.v1'
export type ConsentChoice = 'granted' | 'denied' | null

const suppressedByDnt = ref(false)
const choice = ref<ConsentChoice>(null)
let client: AnalyticsClient | null = null
let initialized = false

function dntActive() {
  try { return typeof navigator !== 'undefined' && navigator.doNotTrack === '1' } catch { return true }
}

function removeLocal(key: string) { try { window.localStorage.removeItem(key) } catch { /* fail closed */ } }
function removeSession(key: string) { try { window.sessionStorage.removeItem(key) } catch { /* fail closed */ } }
function clearIdentifiers() { removeLocal(VISITOR_KEY); removeSession(SESSION_KEY) }

function failClosed() {
  client?.destroy(); client = null; choice.value = null
  removeLocal(CONSENT_KEY); clearIdentifiers()
}

export function initializeAnalyticsConsent() {
  if (initialized) return
  initialized = true
  if (dntActive()) {
    suppressedByDnt.value = true
    choice.value = null
    clearIdentifiers()
    return
  }
  try {
    const saved = window.localStorage.getItem(CONSENT_KEY)
    if (saved === 'granted') {
      choice.value = 'granted'
      client = new AnalyticsClient({ allowed: () => choice.value === 'granted', dnt: dntActive })
      return
    }
    choice.value = saved === 'denied' ? 'denied' : null
    if (saved !== null && saved !== 'denied') removeLocal(CONSENT_KEY)
    clearIdentifiers()
  } catch { failClosed() }
}

export function useAnalyticsConsent() {
  initializeAnalyticsConsent()
  const accept = () => {
    if (dntActive()) { suppressedByDnt.value = true; failClosed(); return }
    try {
      window.localStorage.setItem(CONSENT_KEY, 'granted')
      choice.value = 'granted'
      client?.destroy()
      client = null
      const next = new AnalyticsClient({ allowed: () => choice.value === 'granted', dnt: dntActive })
      client = next
    } catch { failClosed() }
  }
  const reject = () => {
    client?.destroy(); client = null; clearIdentifiers()
    try { window.localStorage.setItem(CONSENT_KEY, 'denied'); choice.value = 'denied' } catch { failClosed() }
  }
  const withdraw = () => {
    client?.destroy(); client = null; clearIdentifiers(); choice.value = 'denied'
    try { window.localStorage.setItem(CONSENT_KEY, 'denied') } catch { failClosed() }
  }
  return {
    choice: readonly(choice), suppressedByDnt: readonly(suppressedByDnt),
    promptVisible: computed(() => !suppressedByDnt.value && choice.value === null), accept, reject, withdraw,
  }
}

export function trackAnalytics(input: AnalyticsTrackInput) {
  if (dntActive()) { suppressedByDnt.value = true; client?.destroy(); client = null; clearIdentifiers(); return }
  client?.track(input)
}
export function flushAnalytics(keepalive = false) {
  if (dntActive()) { suppressedByDnt.value = true; client?.destroy(); client = null; clearIdentifiers(); return Promise.resolve() }
  return client?.flush(keepalive) || Promise.resolve()
}

export function resetAnalyticsForTests() {
  client?.destroy(); client = null; initialized = false; suppressedByDnt.value = false; choice.value = null
}
