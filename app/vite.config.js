import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://home.93gushi.com:8088',
        changeOrigin: true,
        rewrite: (path) => path
      }
    }
  }
})
