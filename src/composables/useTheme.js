import { computed, ref, watchEffect } from 'vue'
import { useMedia } from './useMedia'

// 主题：light 浅色 | dark 深色 | system 跟随系统。选择存在本地
const KEY = 'aido.theme'
const mode = ref(read())
let systemDark
let started = false

function read() {
  try {
    const v = localStorage.getItem(KEY)
    if (v === 'light' || v === 'dark' || v === 'system') return v
  } catch {}
  return 'system'
}

// 手机浏览器地址栏 / 状态栏颜色，与 --window 一致
const BAR_COLOR = { light: '#ffffff', dark: '#000000' }

function apply(theme) {
  const root = document.documentElement
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.content = BAR_COLOR[theme]
  if (root.dataset.theme === theme) return
  // 切换那一帧关掉所有过渡，等样式生效后再恢复，避免颜色参差不齐地渐变
  root.classList.add('theme-switching')
  root.dataset.theme = theme
  requestAnimationFrame(() => requestAnimationFrame(() => root.classList.remove('theme-switching')))
}

export function useTheme() {
  systemDark ||= useMedia('(prefers-color-scheme: dark)')
  const resolved = computed(() => (mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value))

  if (!started) {
    started = true
    watchEffect(() => {
      apply(resolved.value)
      try {
        localStorage.setItem(KEY, mode.value)
      } catch {}
    })
  }

  return { mode, resolved, setMode: (m) => (mode.value = m) }
}
