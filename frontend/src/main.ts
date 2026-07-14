import './assets/main.css'
import '@fontsource/instrument-serif/latin-400.css'
import '@fontsource/instrument-serif/latin-400-italic.css'
import '@fontsource/manrope/latin-400.css'
import '@fontsource/manrope/latin-500.css'
import '@fontsource/manrope/latin-600.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { initializeLocale } from './composables/useLocale'

initializeLocale()

const app = createApp(App)

app.use(router)

app.mount('#app')
