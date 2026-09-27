import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api'
import { useWorkspace } from './workspace'

// 记忆页的数据。放在 store 里，其他标签页改动时推送来的快照可以直接替换
export const useMemory = defineStore('memory', () => {
  const loaded = ref(false)
  const learning = ref(true)
  const profile = ref([])
  const items = ref([])
  const sources = ref([])

  function apply(m) {
    learning.value = m.learning
    profile.value = m.profile
    items.value = m.items
    sources.value = m.sources
    loaded.value = true
  }
  async function load() {
    const m = await useWorkspace().attempt(() => api.memory())
    if (m) apply(m)
  }
  // 推送来的快照只在看过记忆页之后才需要；没加载过的等打开时再拉
  function receive(m) {
    if (loaded.value) apply(m)
  }
  async function reload() {
    if (loaded.value) await load()
  }

  // 「从日常工作中学习」存在个人偏好里
  async function setLearning(on) {
    const prev = learning.value
    learning.value = on
    if (!(await useWorkspace().attempt(() => api.savePreferences({ memoryLearning: on })))) learning.value = prev
  }

  async function removeItem(id) {
    const before = items.value
    items.value = items.value.filter((i) => i.id !== id)
    const ok = await useWorkspace().attempt(() => api.removeMemoryItem(id).then(() => true))
    if (!ok) items.value = before
  }
  // 返回是否保存成功；失败时调用方保持编辑状态
  async function editItem(item, text) {
    const saved = await useWorkspace().attempt(() => api.editMemoryItem(item.id, text))
    if (saved) Object.assign(item, saved)
    return !!saved
  }
  async function addTag(group, tag) {
    const saved = await useWorkspace().attempt(() => api.addProfileTag(group.group, tag))
    if (saved && !group.tags.some((t) => t.id === saved.id)) group.tags.push(saved)
    return !!saved
  }
  async function toggleSource(s, on) {
    s.on = on
    if (!(await useWorkspace().attempt(() => api.toggleMemorySource(s.key, on)))) s.on = !on
  }

  return { loaded, learning, profile, items, sources, load, receive, reload, setLearning, removeItem, editItem, addTag, toggleSource }
})
