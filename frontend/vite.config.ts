import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [preact()],
  server: {
    port: 3001,
    proxy: {
      '/rss': 'http://localhost:3000',
      '/api': 'http://localhost:3000',
      '/files': 'http://localhost:3000'
    }
  }
})
