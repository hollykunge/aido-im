<script setup>
import { computed } from 'vue'
import { useWorkspace } from '@/stores/workspace'

// 消息正文：把「@姓名」渲染成高亮；@ 到自己的更醒目
const props = defineProps({ text: { type: String, default: '' } })
const ws = useWorkspace()

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const parts = computed(() => {
  const names = Object.values(ws.users).map((u) => u.name).filter(Boolean)
  if (!names.length || !props.text.includes('@')) return [{ text: props.text }]
  // 长名字优先，避免「林舟」先匹配掉「林舟舟」
  const re = new RegExp(`@(${names.sort((a, b) => b.length - a.length).map(escape).join('|')})`, 'g')
  const out = []
  let last = 0
  for (const m of props.text.matchAll(re)) {
    if (m.index > last) out.push({ text: props.text.slice(last, m.index) })
    out.push({ text: m[0], mention: true, me: m[1] === ws.me?.name })
    last = m.index + m[0].length
  }
  if (last < props.text.length) out.push({ text: props.text.slice(last) })
  return out
})
</script>

<template>
  <span class="message-text"><template v-for="(p, i) in parts" :key="i"><span v-if="p.mention" class="at" :class="{ me: p.me }">{{ p.text }}</span><template v-else>{{ p.text }}</template></template></span>
</template>

<style scoped>
.message-text {
  white-space: pre-wrap;
  word-break: break-word;
}
.at {
  color: var(--blue);
  font-weight: 500;
}
.at.me {
  padding: 0 3px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--blue) 14%, transparent);
}
</style>
