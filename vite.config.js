import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  server: {
    proxy: {
      '/api/v1/tasks': {
        target: 'http://172.16.25.79:8080',
        changeOrigin: true,
        secure: false,
      },
      '/api/v1/robots': {
        target: 'http://172.16.25.79:8080',
        changeOrigin: true,
        secure: false,
      },
      '/api/robot': {
        target: 'http://172.16.25.79:8080',
        changeOrigin: true,
        secure: false,
      },
      '/scheduler': {
        target: 'http://172.16.25.79:8080',
        changeOrigin: true,
        secure: false,
      }
    }
  }
})
