import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource-variable/inter'
import '@fontsource-variable/jetbrains-mono'
import './style.css'
import App from './App.vue'
import { applyTheme } from './lib/theme'

applyTheme()

createApp(App).use(createPinia()).mount('#app')
