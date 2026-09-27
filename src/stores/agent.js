import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { api } from '@/api'
import { useWorkspace } from './workspace'
import { LOOK_DEFAULT, resolveLook } from '@/agentLook'
import { useTheme } from '@/composables/useTheme'

let seq = 0
const tmpId = () => `tmp-${++seq}`
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

export const useAgent = defineStore('agent', () => {
  // 名字、简介、快捷指令由后端配置（见 init）
  const profile = ref({ name: '', tagline: '', available: true, quickPrompts: [] })
  const name = computed(() => profile.value.name)
  const feed = ref([])
  const busy = ref(false)

  // AI 可用性：后端关闭小A，或在设置里模拟不可用时，界面上的 AI 元素直接隐藏，基础功能照常使用
  const simulatedDown = ref(false)
  const aiOk = computed(() => profile.value.available && !simulatedDown.value)
  const checking = ref(false)
  function setAiDown(down) {
    simulatedDown.value = down
  }
  async function retry() {
    checking.value = true
    simulatedDown.value = false
    try {
      profile.value = await api.agent()
      if (profile.value.available && !feed.value.length) feed.value = await api.feed()
    } catch {}
    checking.value = false
  }

  // 小A的自动化能力开关
  const capabilities = ref([])
  async function toggleCapability(c, on) {
    const ws = useWorkspace()
    c.on = on
    const saved = await ws.attempt(() => api.toggleCapability(c.key, on))
    if (saved) Object.assign(c, saved)
    else c.on = !on
  }

  // 虚拟形象的情绪：thinking 思考 / happy 开心 / listening 倾听（用户在输入）/ idle
  const typing = ref(false)
  const happy = ref(false)
  let happyTimer
  function cheer(ms = 1600) {
    happy.value = true
    clearTimeout(happyTimer)
    happyTimer = setTimeout(() => (happy.value = false), ms)
  }
  // 虚拟形象外观（配色、眼镜）：本地先用缓存避免闪烁，启动后以后端保存的为准
  const look = ref(readLook())
  const palette = computed(() => resolveLook(look.value))
  watch(
    look,
    (v) => {
      try {
        localStorage.setItem('aido.agentLook', JSON.stringify(v))
      } catch {}
    },
    { deep: true },
  )
  function readLook() {
    try {
      const v = JSON.parse(localStorage.getItem('aido.agentLook'))
      if (v && typeof v === 'object') return { ...LOOK_DEFAULT, ...v }
    } catch {}
    return { ...LOOK_DEFAULT }
  }
  function resetLook() {
    look.value = { ...LOOK_DEFAULT }
  }

  const mood = computed(() => {
    if (!aiOk.value) return 'offline'
    if (busy.value) return 'thinking'
    if (happy.value) return 'happy'
    if (typing.value) return 'listening'
    return 'idle'
  })

  // —— 个人偏好（主题、形象）：以后端为准，本地改动自动保存回去 ——
  const theme = useTheme()
  // 后端当前保存的值。本地值和它一样时不再保存——收到其他标签页的推送后也是这样，避免标签页之间来回回写
  const saved = { theme: null, look: null }
  const lookKey = (v) => JSON.stringify({ ...LOOK_DEFAULT, ...v })
  function applyPreferences(prefs) {
    saved.theme = prefs.theme
    saved.look = lookKey(prefs.agentLook)
    theme.setMode(prefs.theme)
    look.value = { ...LOOK_DEFAULT, ...prefs.agentLook }
  }
  // 连续改动合并成一次保存
  let saveTimer
  let pending = {}
  function save(patch) {
    pending = { ...pending, ...patch }
    clearTimeout(saveTimer)
    saveTimer = setTimeout(() => {
      api.savePreferences(pending).catch(() => {})
      pending = {}
    }, 400)
  }
  function watchPreferences() {
    watch(theme.mode, (m) => {
      if (m === saved.theme) return
      saved.theme = m
      save({ theme: m })
    })
    watch(
      look,
      (v) => {
        if (lookKey(v) === saved.look) return
        saved.look = lookKey(v)
        save({ agentLook: v })
      },
      { deep: true },
    )
  }

  // —— 启动：小A资料、对话流、能力开关、个人偏好 ——
  async function init() {
    const [p, caps, prefs] = await Promise.all([api.agent(), api.capabilities(), api.preferences()])
    profile.value = p
    capabilities.value = caps
    if (p.available) feed.value = await api.feed()
    applyPreferences(prefs)
    watchPreferences()
  }
  // 推送重连后：偏好和能力开关可能在断线期间被别处改过
  async function reloadSettings() {
    const [caps, prefs] = await Promise.all([api.capabilities(), api.preferences()]).catch(() => [])
    if (caps) capabilities.value = caps
    if (prefs) applyPreferences(prefs)
  }

  /**
   * 发送一条消息给 Agent。context 描述主窗口当前在看什么：
   * { view: 'chat' | 'todo' | 'memory' | 'search', label, convId }
   */
  async function send(text, context) {
    if (!text.trim() || busy.value || !aiOk.value) return
    const ws = useWorkspace()
    busy.value = true
    // 先把用户这句话和「思考中」放上去，拿到后端回复后再逐条播放思考步骤
    const said = { id: tmpId(), type: 'user', text, context: context?.label }
    feed.value.push(said)
    feed.value.push({ id: tmpId(), type: 'thinking', steps: ['理解你的问题'], current: 0 })
    const thinking = feed.value[feed.value.length - 1]

    const reply = await ws.attempt(() =>
      api.ask(text, context && { view: context.view, label: context.label, conversationId: context.convId }),
    )
    if (!reply) {
      feed.value = feed.value.filter((f) => f.id !== thinking.id)
      busy.value = false
      return
    }
    thinking.steps = reply.steps
    for (let i = 0; i < reply.steps.length; i++) {
      thinking.current = i
      await wait(650)
    }
    const [userItem, ...items] = reply.items
    feed.value = feed.value.filter((f) => f.id !== thinking.id).map((f) => (f.id === said.id ? userItem : f))
    feed.value.push(...items)
    // 「重新整理」会在后端新增待确认的 TODO
    if (items.some((it) => it.type === 'proposal')) await ws.reloadTodos()
    busy.value = false
    cheer()
  }

  // 提议卡片引用的是 TODO 里「待确认」的项，确认/忽略直接改 TODO 状态
  function acceptProposal(item) {
    useWorkspace().acceptTodos(item.todoIds)
    cheer()
  }
  function dismissProposal(item) {
    useWorkspace().dismissTodos(item.todoIds)
  }

  // 其他标签页产生的卡片（实时推送）：已有的替换，没有的追加
  function receiveItems(items) {
    items.forEach((it) => (feed.value.some((f) => f.id === it.id) ? replaceItem(it) : feed.value.push(it)))
  }
  async function reloadFeed() {
    if (!aiOk.value || busy.value) return
    const items = await api.feed().catch(() => null)
    if (items) feed.value = items
  }

  // —— 草稿卡片 ——
  function replaceItem(item) {
    const i = feed.value.findIndex((f) => f.id === item.id)
    if (i >= 0) feed.value[i] = item
  }
  // 放进聊天输入框，由用户改完再发
  async function insertDraft(item) {
    const ws = useWorkspace()
    ws.pendingDraft = { convId: item.convId, text: item.text }
    const updated = await ws.attempt(() => api.insertDraft(item.id))
    if (updated) replaceItem(updated)
  }
  // 直接发送；后端顺带勾掉这个会话里「需要回复」的 TODO
  async function sendDraft(item) {
    const ws = useWorkspace()
    const r = await ws.attempt(() => api.sendDraft(item.id))
    if (!r) return
    replaceItem(r.item)
    ws.applySent(r.message)
    if (r.completedTodo) ws.applyTodos([r.completedTodo])
    cheer()
  }

  return {
    profile, name, feed, busy, mood, typing, look, palette, aiOk, checking, capabilities, setAiDown, retry, init,
    cheer, resetLook, send, acceptProposal, dismissProposal, insertDraft, sendDraft, toggleCapability, receiveItems,
    reloadFeed, applyPreferences, reloadSettings,
  }
})
