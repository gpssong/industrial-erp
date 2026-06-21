import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    port: 5173,
    host: '0.0.0.0',
    // Vite 5+ 默认拒绝非白名单 Host 头访问, 避免 DNS rebinding.
    // 字符串规则: 精确匹配, 或 ".example.com" (以 . 开头) 匹配该域名及所有子域.
    // 注意: Vite 5 的 isHostAllowed 对 IPv4 地址直接放行 (内部用 net.isIP 短路),
    //       所以 192.168.x.x / 10.x.x.x 局域网访问不需要再写.
    //       该函数不处理 RegExp 实例, 不要把 RegExp 放进数组.
    allowedHosts: [
      'localhost',
      '127.0.0.1',
      // 生产域名: ".93gushi.com" 以 . 开头是 Vite 子域通配, 一行覆盖
      //   根域 93gushi.com + www/home/erp 等所有子域.
      //   新加子域不用改这里.
      '.93gushi.com',
      // 局域网 macOS 主机名解析: *.local 全部放行
      '.local'
    ],
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
