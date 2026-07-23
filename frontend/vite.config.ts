import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Backend context-path'i (application.yml -> server.servlet.context-path)
      // dev sunucusu üzerinden proxy'liyoruz. Böylece CORS'a gerek kalmıyor.
      '/cdr-generator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})