import { defineConfig } from 'vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'

export default defineConfig({
  plugins: [
    scalaJSPlugin({
      cwd: '.',
      projectID: 'frontendScala'
    })
  ],
  server: {
    port: 5174
  },
  publicDir: 'public'
})
