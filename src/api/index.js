// 后端接口。返回前统一换成前端使用的字段名（见各 to* 函数），页面不直接依赖后端字段
import { BASE, http, qs } from './http'
import { listTime, hm, fileSize, shortDate } from './format'

export { ApiError, UNAUTHORIZED_EVENT } from './http'

// —— 字段转换 ——

export const toConv = (c) => ({
  id: c.id,
  type: c.type,
  name: c.title, // 群名；私聊为对方名字
  userId: c.peerUserId,
  color: c.color,
  members: c.memberCount,
  last: c.lastMessage || '',
  lastAt: c.lastMessageAt,
  time: listTime(c.lastMessageAt),
  unread: c.unread,
  pinned: c.pinned,
  muted: c.muted,
  agentFlag: c.agentFlag,
})

// 附件地址：安全的图片默认在页面里显示（缩略图），download=true 时强制下载
export const fileUrl = (messageId, download = false) => `${BASE}/files/${messageId}${download ? '?download' : ''}`
const INLINE_IMAGES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']

export const toMsg = (m) => ({
  id: m.id,
  convId: m.conversationId,
  from: m.senderId,
  text: m.text,
  file: m.file && {
    name: m.file.name,
    size: fileSize(m.file.size),
    type: m.file.type,
    // 演示数据里的文件消息只有文件名，没有可下载的内容
    available: m.file.available,
    image: m.file.available && INLINE_IMAGES.includes(m.file.type),
  },
  mention: m.mentionsMe,
  sentAt: m.sentAt,
  time: hm(m.sentAt), // 日期由消息列表里的分隔线显示
})

export const toTodo = (t) => ({
  id: t.id,
  title: t.title,
  status: t.status,
  kind: t.kind,
  priority: t.priority,
  today: t.today,
  due: t.due || '',
  dueAt: t.dueAt,
  note: t.note,
  createdBy: t.createdBy,
  source: t.source && {
    convId: t.source.conversationId,
    convName: t.source.conversationName,
    msgId: t.source.messageId,
    from: t.source.senderId,
    fromName: t.source.senderName,
    text: t.source.text,
    sentAt: t.source.sentAt,
  },
})

// 草稿卡片的会话字段与前端组件保持一致
export const toFeedItem = (it) =>
  it.type === 'draft' ? { ...it, convId: it.conversationId, convName: it.conversationName } : it

const toMemoryItem = (i) => ({ id: i.id, text: i.text, source: i.source, date: shortDate(i.createdAt) })
const toSource = (s) => ({ key: s.key, label: s.label, desc: s.description, on: s.enabled })
export const toMemory = (m) => ({ ...m, items: m.items.map(toMemoryItem), sources: m.sources.map(toSource) })
export const toCapability = (c) => ({ key: c.key, title: c.title, desc: c.description, on: c.enabled })

// —— 接口 ——

export const api = {
  login: (login, password) => http.post('/auth/login', { login, password }),
  logout: () => http.post('/auth/logout'),
  changePassword: (currentPassword, newPassword) => http.post('/me/password', { currentPassword, newPassword }),
  me: () => http.get('/me'),
  updateProfile: (patch) => http.patch('/me', patch),
  users: () => http.get('/users'),
  preferences: () => http.get('/me/preferences'),
  savePreferences: (patch) => http.patch('/me/preferences', patch),

  conversations: async () => (await http.get('/conversations')).map(toConv),
  conversation: async (id) => toConv(await http.get(`/conversations/${id}`)),
  openDm: async (userId) => toConv(await http.post('/conversations/dm', { userId })),
  messages: async (convId) => (await http.get(`/conversations/${convId}/messages?limit=200`)).map(toMsg),
  sendMessage: async (convId, text) => toMsg(await http.post(`/conversations/${convId}/messages`, { text })),
  sendFile: async (convId, file, opts) => toMsg(await http.upload(`/conversations/${convId}/files`, file, opts)),
  members: (convId) => http.get(`/conversations/${convId}/members`),
  markRead: (convId) => http.post(`/conversations/${convId}/read`),
  summary: (convId) => http.get(`/conversations/${convId}/summary`),
  suggestedReplies: (convId) => http.get(`/conversations/${convId}/suggested-replies`),

  todos: async () => (await http.get('/todos')).map(toTodo),
  addTodo: async (title) => toTodo(await http.post('/todos', { title })),
  todoFromMessage: async (messageId) => toTodo(await http.post('/todos/from-message', { messageId })),
  updateTodo: async (id, patch) => toTodo(await http.patch(`/todos/${id}`, patch)),
  setTodoStatus: async (ids, status) => (await http.post('/todos/status', { ids, status })).map(toTodo),

  memory: async () => toMemory(await http.get('/memory')),
  editMemoryItem: async (id, text) => toMemoryItem(await http.patch(`/memory/items/${id}`, { text })),
  removeMemoryItem: (id) => http.del(`/memory/items/${id}`),
  // 同一分组里已有的标签不会重复创建，返回已有那一个
  addProfileTag: (group, tag) => http.post('/memory/profile-tags', { group, tag }),
  toggleMemorySource: (key, enabled) => http.patch(`/memory/sources/${key}`, { enabled }),

  // 一次取回各类结果（每类最多 50 条），前端切换范围时不用再请求
  search: (q, includeSuggested) => http.get(`/search${qs({ q, scope: 'all', perSection: 50, includeSuggested })}`),
  searchHistory: () => http.get('/search/history'),
  rememberSearch: (query) => http.post('/search/history', { query }),
  clearSearchHistory: () => http.del('/search/history'),

  agent: () => http.get('/agent'),
  feed: async () => (await http.get('/agent/feed')).map(toFeedItem),
  ask: async (text, context) => {
    const r = await http.post('/agent/messages', { text, context })
    return { steps: r.steps, items: r.items.map(toFeedItem) }
  },
  insertDraft: async (itemId) => toFeedItem(await http.post(`/agent/feed/${itemId}/draft/insert`)),
  sendDraft: async (itemId) => {
    const r = await http.post(`/agent/feed/${itemId}/draft/send`)
    return { item: toFeedItem(r.item), message: toMsg(r.message), completedTodo: r.completedTodo && toTodo(r.completedTodo) }
  },
  realtimeTicket: () => http.post('/realtime/ticket'),
  capabilities: async () => (await http.get('/agent/capabilities')).map(toCapability),
  toggleCapability: async (key, enabled) => toCapability(await http.patch(`/agent/capabilities/${key}`, { enabled })),
}
