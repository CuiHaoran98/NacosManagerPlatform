/**
 * NCG 配置治理平台 —— 应用入口
 * 装载顺序：全局样式（tokens → base）→ 字体 → Pinia → Router
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

// 设计 token 必须在组件样式之前加载
import './styles/tokens.css'
import './styles/base.css'

// 显示字体：DM Sans + Noto Sans SC（frontend-design-pro：禁 Arial/Inter/system-ui 作显示字体）
import '@fontsource/dm-sans/400.css'
import '@fontsource/dm-sans/500.css'
import '@fontsource/dm-sans/600.css'
import '@fontsource/noto-sans-sc/400.css'
import '@fontsource/noto-sans-sc/500.css'
import '@fontsource/noto-sans-sc/700.css'
// 数据/代码字体：IBM Plex Mono（tabular-nums）
import '@fontsource/ibm-plex-mono/400.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
