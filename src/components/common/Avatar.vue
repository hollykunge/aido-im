<script setup>
import { computed } from 'vue'
import { useWorkspace } from '@/stores/workspace'

const props = defineProps({
  user: String, // users 中的 id
  name: String,
  color: String,
  size: { type: Number, default: 36 },
  square: Boolean,
})

const ws = useWorkspace()
const info = computed(() => {
  const u = props.user ? ws.users[props.user] : null
  return { name: props.name || u?.name || '?', color: props.color || u?.color || '#94a3b8' }
})
const initial = computed(() => {
  const n = info.value.name
  if (!/[一-龥]/.test(n)) return n.slice(0, 2).toUpperCase()
  // 人名取后两字，群名取前两字
  return n.length <= 3 ? n.slice(-2) : n.slice(0, 2)
})
</script>

<template>
  <span
    class="avatar"
    :class="{ square }"
    :style="{ width: size + 'px', height: size + 'px', '--c': info.color, fontSize: size * 0.34 + 'px' }"
    :title="info.name"
  >{{ initial }}</span>
</template>

<style scoped>
.avatar {
  flex: none;
  display: inline-grid;
  place-items: center;
  border-radius: 50%;
  /* 浅色底 + 同色系深色字（暗色下为深底浅字），比例见 base.css 的 --avatar-bg / --avatar-fg */
  color: color-mix(in srgb, var(--c) var(--avatar-fg), var(--text-1));
  font-weight: 600;
  letter-spacing: -0.02em;
  background: color-mix(in srgb, var(--c) var(--avatar-bg), var(--card));
  box-shadow: inset 0 0 0 0.5px color-mix(in srgb, var(--c) 20%, transparent);
  user-select: none;
}
.avatar.square {
  border-radius: 30%;
}
</style>
