import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:18080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    include: ['src/**/*.spec.ts', 'tests/**/*.spec.ts'],
    exclude: ['tests/e2e/**', 'node_modules/**', 'dist/**'],
  },
  build: {
    manifest: true,
    rollupOptions: {
      // The backend renders the HTML shell and resolves this stable manifest key.
      input: fileURLToPath(new URL('./src/main.ts', import.meta.url)),
    },
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
