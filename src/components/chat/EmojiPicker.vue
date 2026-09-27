<script setup>
import { computed, ref } from 'vue'
import { EMOJI_GROUPS } from './emoji'

const emit = defineEmits(['pick'])

// 最近使用的表情记在本机浏览器里（只是方便，丢了也不影响）
const RECENT_KEY = 'aido.recentEmoji'
const RECENT_MAX = 16
const recent = ref(readRecent())
function readRecent() {
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY))
    return Array.isArray(v) ? v.slice(0, RECENT_MAX) : []
  } catch {
    return []
  }
}

const groups = computed(() => [
  ...(recent.value.length ? [{ key: 'recent', label: '最近使用', list: recent.value }] : []),
  ...EMOJI_GROUPS,
])
const active = ref(groups.value[0].key)
const current = computed(() => groups.value.find((g) => g.key === active.value) || groups.value[0])

function pick(e) {
  recent.value = [e, ...recent.value.filter((x) => x !== e)].slice(0, RECENT_MAX)
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(recent.value))
  } catch {}
  emit('pick', e)
}
</script>

<template>
  <div class="emoji-picker" role="dialog" aria-label="表情">
    <nav class="tabs">
      <button
        v-for="g in groups"
        :key="g.key"
        :class="{ on: current.key === g.key }"
        type="button"
        @click="active = g.key"
      >
        {{ g.label }}
      </button>
    </nav>
    <div class="grid">
      <!-- mousedown.prevent：点表情时输入框不失焦，光标位置保留 -->
      <button v-for="e in current.list" :key="e" type="button" class="emoji" :title="e" @mousedown.prevent @click="pick(e)">
        {{ e }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.emoji-picker {
  width: 312px;
  max-width: calc(100vw - 32px);
  padding: 8px;
  border-radius: 16px;
  background: var(--popover);
  box-shadow: var(--shadow-pop), 0 0 0 0.5px var(--line-strong);
}
.tabs {
  display: flex;
  gap: 2px;
  margin-bottom: 6px;
}
.tabs button {
  height: 26px;
  padding: 0 10px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-2);
}
.tabs button:hover {
  background: var(--hover);
}
.tabs button.on {
  background: var(--hover-strong);
  color: var(--text-1);
  font-weight: 600;
}
.grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 2px;
  max-height: 188px;
  overflow-y: auto;
}
.emoji {
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  border-radius: 8px;
  font-size: 22px;
  line-height: 1;
}
.emoji:hover {
  background: var(--hover);
}
</style>
