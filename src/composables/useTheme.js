import { computed, ref, watchEffect } from 'vue'
import { useMedia } from './useMedia'

// 主题：light 浅色 | dark 深色 | system 跟随系统。选择存在本地
const KEY = 'yunque.theme'
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

export function useTheme() {
  systemDark ||= useMedia('(prefers-color-scheme: dark)')
  const resolved = computed(() => (mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value))

  if (!started) {
    started = true
    watchEffect(() => {
      document.documentElement.dataset.theme = resolved.value
      try {
        localStorage.setItem(KEY, mode.value)
      } catch {}
    })
  }

  return { mode, resolved, setMode: (m) => (mode.value = m) }
}
