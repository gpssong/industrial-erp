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
        changeOrigin: true,
        // 修复 PUT/DELETE 请求被 Vite proxy 拦截返回 403 的问题
        // (Vite 5.4+ 对 PUT 请求做了特殊处理, 需要显式禁用 CORS 校验)
        secure: false,
        // 让所有方法都正常转发 (默认只转发 GET/POST/PUT/DELETE)
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            // 显式设置 Origin, 让后端 CORS 配置生效
            if (req.headers.origin) {
              proxyReq.setHeader('origin', req.headers.origin)
            }
          })
        }
      }
    }
  },
  base: './', // 相对路径, 兼容 file:// 协议 (Electron 本地模式) 和 http:// (远端模式)
  define: {
    // 注入版本号 (构建时替换, 运行时可直接用)
    __APP_VERSION__: JSON.stringify(require('./package.json').version),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString().slice(0, 10))
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
