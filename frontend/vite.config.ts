import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// NCG 前端构建配置：Element Plus 按需引入（unplugin-vue-components + ElementPlusResolver）
// dev 联调代理：/api -> http://127.0.0.1:8085（Base path 保持 /api/v1，无需 rewrite）
export default defineConfig({
  plugins: [
    vue(),
    Components({
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
      dts: 'src/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': new URL('./src', import.meta.url).pathname,
    },
  },
  server: {
    host: true,        // 监听 0.0.0.0：NAS 局域网访问必需（默认仅回环，外部连接被拒绝）
    port: 5173,
    strictPort: true,  // 端口被占直接失败，不悄悄漂移到 5174
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8085',
        changeOrigin: true,
      },
    },
  },
})
