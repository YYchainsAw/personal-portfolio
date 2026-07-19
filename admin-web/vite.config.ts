import { fileURLToPath, URL } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig({
  base: '/admin/',
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        configure(proxy) {
          // The browser is same-origin with Vite; forwarding its Origin turns this
          // internal development hop into an unnecessary backend CORS request.
          proxy.on('proxyReq', (request) => request.removeHeader('origin'))
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    exclude: [...configDefaults.exclude, 'tests/e2e/**'],
    maxWorkers: 4,
    restoreMocks: true,
    setupFiles: [],
  },
})
