import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { useMedia } from '@/composables/useMedia'
import { api } from '@/api'
import { listTime } from '@/api/format'

export const useWorkspace = defineStore('workspace', () => {
  // —— 基础数据：启动时从后端加载（见 init）——
  const me = ref(null)
  const users = ref({}) // id → 用户
  const conversations = ref([])
  const messages = ref({}) // 会话 id → 消息列表，打开会话时按需加载
  const todos = ref([]) // TODO 全部由 Agent 从消息中整理而来，也可手动新建
  const summaries = ref({}) // 会话 id → 小雀摘要（没有时为 null）
  const replies = ref({}) // 会话 id → 建议回复
  const members = ref({}) // 会话 id → 成员（@ 提及用），按需加载

  async function init() {
    const [meRes, userList, convs, todoList] = await Promise.all([api.me(), api.users(), api.conversations(), api.todos()])
    me.value = meRes
    users.value = Object.fromEntries(userList.map((u) => [u.id, u]))
    conversations.value = convs
    todos.value = todoList
  }
  const meId = computed(() => me.value?.id)

  // 某人改了姓名或头像颜色（自己改的，或推送来的别人的改动）：通讯录、私聊标题、TODO 来源都跟着变
  function applyUser(u) {
    users.value[u.id] = { ...users.value[u.id], ...u }
    if (u.id === meId.value) me.value = { ...me.value, name: u.name, color: u.color }
    conversations.value.forEach((c) => c.type === 'dm' && c.userId === u.id && (c.name = u.name))
    Object.values(members.value).forEach((list) => list?.forEach((m) => m.id === u.id && Object.assign(m, u)))
    todos.value.forEach((t) => {
      if (!t.source) return
      if (t.source.from === u.id) t.source.fromName = u.name
      if (getConv(t.source.convId)?.userId === u.id) t.source.convName = u.name
    })
  }
  // 改自己的资料：先改本地让界面立即变化，失败时恢复；返回是否成功
  async function updateProfile(patch) {
    const before = { ...users.value[meId.value] }
    applyUser({ ...before, ...patch })
    const saved = await attempt(() => api.updateProfile(patch))
    if (!saved) {
      applyUser(before)
      return false
    }
    me.value = saved
    applyUser(saved)
    return true
  }

  // —— 轻提示：接口失败时告诉用户 ——
  const toast = ref(null)
  let toastTimer
  function notify(text) {
    toast.value = text
    clearTimeout(toastTimer)
    toastTimer = setTimeout(() => (toast.value = null), 3200)
  }
  // 包一层：失败时提示并返回 null，调用方不必各自 try/catch
  async function attempt(fn) {
    try {
      return await fn()
    } catch (e) {
      notify(e.message)
      return null
    }
  }

  // 窄屏默认收起 Agent；手机宽度下 Agent 展开时占满整屏
  const isNarrow = useMedia('(max-width: 1100px)')
  const isCompact = useMedia('(max-width: 760px)')
  const agentCollapsed = ref(isNarrow.value)
  watch(isNarrow, (narrow) => (agentCollapsed.value = narrow))
  // 手机宽度下 Agent 把用户带去主窗口时，收起 Agent 让出屏幕
  function revealWorkspace() {
    if (isCompact.value) agentCollapsed.value = true
  }

  // Agent 容器宽度（可拖拽），记在本地
  const AGENT_W = { min: 320, max: 720, default: 440 }
  const agentWidth = ref(readWidth())
  watch(agentWidth, (w) => {
    try {
      localStorage.setItem('yunque.agentWidth', String(w))
    } catch {}
  })
  function readWidth() {
    try {
      const w = Number(localStorage.getItem('yunque.agentWidth'))
      if (w >= AGENT_W.min && w <= AGENT_W.max) return w
    } catch {}
    return AGENT_W.default
  }
  // 设置面板（标题栏齿轮 / 小雀头像打开）
  const settingsOpen = ref(false)
  // Agent 写给主窗口聊天输入框的草稿：{ convId, text }
  const pendingDraft = ref(null)
  const highlightTodoIds = ref([])
  // 聊天页当前打开的会话（ChatView 维护）
  const openConvId = ref(null)

  const unreadTotal = computed(() =>
    conversations.value.filter((c) => !c.muted).reduce((n, c) => n + c.unread, 0),
  )
  const openTodoCount = computed(() => todos.value.filter((t) => t.status === 'open').length)
  const todayTodoCount = computed(() => todos.value.filter((t) => t.status === 'open' && t.today).length)
  const suggestedCount = computed(() => todos.value.filter((t) => t.status === 'suggested').length)

  // —— 会话与消息 ——
  function convTitle(c) {
    return c?.name || ''
  }
  function getConv(id) {
    return conversations.value.find((c) => c.id === id)
  }
  // 打开与某人的私聊，没有会话时后端新建
  async function openDm(userId) {
    const c = await attempt(() => api.openDm(userId))
    if (!c) return null
    if (!getConv(c.id)) conversations.value.push(c)
    return c.id
  }
  async function loadMessages(convId) {
    const list = await attempt(() => api.messages(convId))
    if (list) messages.value[convId] = list
  }
  // 摘要与建议回复只在 AI 可用时显示，按会话缓存
  async function loadAssist(convId) {
    if (!(convId in summaries.value)) summaries.value[convId] = await api.summary(convId).catch(() => null)
    if (!(convId in replies.value)) replies.value[convId] = await api.suggestedReplies(convId).catch(() => [])
  }
  async function loadMembers(convId) {
    if (!members.value[convId]) members.value[convId] = await api.members(convId).catch(() => null)
    return members.value[convId] || []
  }
  function markRead(id) {
    const c = getConv(id)
    if (!c?.unread) return
    c.unread = 0
    api.markRead(id).catch(() => {})
  }
  // 新消息（自己发的，或实时推送来的）：追加到已加载的消息列表，更新会话预览、未读数并把会话移到最前。
  // 会话还没加载过消息时不建半截列表，打开时会整体加载
  async function receiveMessage(msg, unread) {
    const list = messages.value[msg.convId]
    if (list && !list.some((m) => m.id === msg.id)) {
      list.push(msg)
      // 并发发送时推送可能先后颠倒，按 id（即落库顺序）排好
      list.sort((a, b) => a.id - b.id)
    }
    let c = getConv(msg.convId)
    // 别人新建的私聊，本地还没有这个会话
    if (!c) {
      c = await api.conversation(msg.convId).catch(() => null)
      if (!c) return
    } else {
      const content = msg.text ?? (msg.file?.image ? '[图片]' : `[文件] ${msg.file?.name}`)
      const fromOther = c.type === 'group' && msg.from !== meId.value
      Object.assign(c, {
        last: fromOther ? `${users.value[msg.from]?.name ?? ''}：${content}` : content,
        lastAt: msg.sentAt,
        time: listTime(msg.sentAt),
        unread,
      })
      // 自己回复了，小雀的「待回复」之类标记就不需要了
      if (msg.from === meId.value) c.agentFlag = null
    }
    // 正看着这个会话（且页面在前台）时收到的消息直接算已读
    if (c.id === openConvId.value && document.visibilityState === 'visible') markRead(c.id)
    conversations.value = [c, ...conversations.value.filter((x) => x.id !== c.id)]
  }
  const applySent = (msg) => receiveMessage(msg, 0)
  function readElsewhere(convId) {
    const c = getConv(convId)
    if (c) c.unread = 0
  }
  // 连上推送后重新拉一次：补上断线期间、以及订阅生效前漏掉的改动
  async function resync() {
    const [convs, todoList, userList] = await Promise.all([api.conversations(), api.todos(), api.users()]).catch(() => [])
    if (convs) conversations.value = convs
    if (todoList) todos.value = todoList
    // 断线期间有人改了姓名或头像颜色
    userList?.forEach(applyUser)
    await Promise.all(Object.keys(messages.value).map(loadMessages))
  }
  async function sendMessage(convId, text) {
    if (!text.trim()) return null
    const msg = await attempt(() => api.sendMessage(convId, text))
    if (msg) applySent(msg)
    return msg
  }

  // —— TODO ——
  function getTodo(id) {
    return todos.value.find((t) => t.id === id)
  }
  // 用后端返回的最新数据替换本地的同一项；新项插到最前面
  function applyTodos(list) {
    list.forEach((t) => {
      const i = todos.value.findIndex((x) => x.id === t.id)
      if (i < 0) todos.value.unshift(t)
      else todos.value[i] = t
    })
  }
  async function reloadTodos() {
    const list = await attempt(() => api.todos())
    if (list) todos.value = list
  }
  // 解析 TODO 的来源：会话、原消息、发送人
  function sourceOf(todo) {
    const s = todo.source
    if (!s) return { conv: null, msg: null, convName: '', user: null }
    const msg = { id: s.msgId, from: s.from, text: s.text }
    return { conv: getConv(s.convId), msg, convName: s.convName, user: users.value[s.from] || { name: s.fromName } }
  }
  function todosForMessage(convId, msgId) {
    return todos.value.filter(
      (t) => t.status !== 'dismissed' && t.source?.convId === convId && t.source?.msgId === msgId,
    )
  }
  // 先改本地让界面立即响应，失败时恢复
  async function setStatus(ids, status) {
    const before = ids.map((id) => [id, getTodo(id)?.status])
    ids.forEach((id) => {
      const t = getTodo(id)
      if (t) t.status = status
    })
    const list = await attempt(() => api.setTodoStatus(ids, status))
    if (!list) {
      before.forEach(([id, s]) => s && (getTodo(id).status = s))
      return
    }
    applyTodos(list)
    if (status === 'open') flashTodos(ids)
  }
  const acceptTodos = (ids) => setStatus(ids, 'open')
  const dismissTodos = (ids) => setStatus(ids, 'dismissed')
  async function toggleTodo(id) {
    const t = getTodo(id)
    if (!t) return
    const prev = t.status
    t.status = prev === 'done' ? 'open' : 'done'
    const updated = await attempt(() => api.updateTodo(id, { status: t.status }))
    if (updated) applyTodos([updated])
    else t.status = prev
  }
  // 手动新建 TODO（不依赖 AI）
  async function addTodo(title) {
    if (!title.trim()) return null
    const todo = await attempt(() => api.addTodo(title.trim()))
    if (todo) {
      applyTodos([todo])
      flashTodos([todo.id])
    }
    return todo
  }
  // 把一条消息转为 TODO，保留原消息来源
  async function todoFromMessage(msgId) {
    const todo = await attempt(() => api.todoFromMessage(msgId))
    if (todo) applyTodos([todo])
    return todo
  }
  function flashTodos(ids) {
    highlightTodoIds.value = ids
    setTimeout(() => (highlightTodoIds.value = []), 2400)
  }

  return {
    me, meId, users, applyUser, updateProfile, openConvId, conversations, messages, todos, summaries, replies, pendingDraft, highlightTodoIds, settingsOpen,
    agentCollapsed, isNarrow, isCompact, revealWorkspace, agentWidth, AGENT_W, toast, notify, attempt, init,
    unreadTotal, openTodoCount, todayTodoCount, suggestedCount,
    members, loadMembers, convTitle, getConv, markRead, sendMessage, applySent, receiveMessage, readElsewhere, resync, openDm, loadMessages,
    loadAssist,
    getTodo, applyTodos, reloadTodos, sourceOf, todosForMessage, acceptTodos, dismissTodos, toggleTodo,
    addTodo, todoFromMessage,
  }
})
