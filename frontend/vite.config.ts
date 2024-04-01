import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3001,
    proxy: {
      '/rss': 'http://localhost:3000',
      '/api': 'http://localhost:3000',
      '/files': 'http://localhost:3000'
    }
  }
})
