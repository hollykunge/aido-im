import { reactive } from 'vue'

// 全局共享的指针位置，所有 Agent 形象共用一个监听
const pointer = reactive({ x: -1, y: -1, t: 0 })
let bound = false
let frame = 0

export function usePointer() {
  if (!bound && typeof window !== 'undefined') {
    bound = true
    window.addEventListener(
      'pointermove',
      (e) => {
        if (frame) return
        frame = requestAnimationFrame(() => {
          frame = 0
          pointer.x = e.clientX
          pointer.y = e.clientY
          pointer.t = Date.now()
        })
      },
      { passive: true },
    )
  }
  return pointer
}
