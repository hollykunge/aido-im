<script setup>
import { computed } from 'vue'

// 把 text 中与 q 匹配的部分高亮（不区分大小写）
const props = defineProps({ text: { type: String, default: '' }, q: String })

const parts = computed(() => {
  const q = props.q?.trim()
  if (!q) return [{ t: props.text }]
  const out = []
  const lower = props.text.toLowerCase()
  const k = q.toLowerCase()
  let i = 0
  while (i < props.text.length) {
    const j = lower.indexOf(k, i)
    if (j < 0) {
      out.push({ t: props.text.slice(i) })
      break
    }
    if (j > i) out.push({ t: props.text.slice(i, j) })
    out.push({ t: props.text.slice(j, j + k.length), hit: true })
    i = j + k.length
  }
  return out
})
</script>

<template>
  <span><template v-for="(p, i) in parts" :key="i"><mark v-if="p.hit">{{ p.t }}</mark><template v-else>{{ p.t }}</template></template></span>
</template>

<style scoped>
mark {
  padding: 0 1px;
  border-radius: 3px;
  background: color-mix(in srgb, var(--warn) 32%, transparent);
  color: inherit;
}
</style>
