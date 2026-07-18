import './assets/main.css'
import '@fontsource/instrument-serif/latin-400.css'
import '@fontsource/instrument-serif/latin-400-italic.css'
import '@fontsource/manrope/latin-400.css'
import '@fontsource/manrope/latin-500.css'
import '@fontsource/manrope/latin-600.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { revealDirective } from '@/directives/reveal'
import { readInitialPayload } from '@/services/initialPayload'
import { publicContentStore } from '@/stores/publicContent'
import { flushAnalytics, initializeAnalyticsConsent } from '@/composables/useAnalyticsConsent'
import { mountWhenRouterReady } from '@/bootstrap'

publicContentStore.replaceInitial(readInitialPayload())
initializeAnalyticsConsent()
window.addEventListener('pagehide', () => { void flushAnalytics(true) })

const app = createApp(App)
app.use(router)
app.directive('reveal', revealDirective)
await mountWhenRouterReady(app, router)

export { app, router }
