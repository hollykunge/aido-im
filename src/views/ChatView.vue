<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Pin, BellOff, Sparkles, Phone, Video, Users, MoreHorizontal, Smile, AtSign, Paperclip,
  FileText, Download, X, WandSparkles, ChevronLeft, ListTodo, ListPlus, CircleCheck, ChevronDown, RotateCcw, Upload,
} from 'lucide-vue-next'
import Avatar from '@/components/common/Avatar.vue'
import AgentOrb from '@/components/common/AgentOrb.vue'
import PillComposer from '@/components/common/PillComposer.vue'
import EmojiPicker from '@/components/chat/EmojiPicker.vue'
import MentionPicker from '@/components/chat/MentionPicker.vue'
import MessageText from '@/components/chat/MessageText.vue'
import { useWorkspace } from '@/stores/workspace'
import { useAgent } from '@/stores/agent'
import { api, fileUrl } from '@/api'
import { dayLabel, fileSize } from '@/api/format'

const route = useRoute()
const router = useRouter()
const ws = useWorkspace()
const agentStore = useAgent()

// 「小雀精选」是 AI 筛选，AI 不可用时不提供
const filters = computed(() => [
  { key: 'all', label: '全部' },
  { key: 'unread', label: '未读' },
  ...(agentStore.aiOk ? [{ key: 'flag', label: `${agentStore.name}精选` }] : []),
])
const filter = ref('all')
watch(() => agentStore.aiOk, (ok) => !ok && filter.value === 'flag' && (filter.value = 'all'))
// 左侧三个分栏：消息坞 / 通讯录 / 群组
const listTabs = [
  { key: 'dock', label: '消息坞' },
  { key: 'contacts', label: '通讯录' },
  { key: 'groups', label: '群组' },
]
const listTab = ref('dock')
const depts = computed(() => {
  const map = new Map()
  Object.values(ws.users)
    .filter((u) => u.id !== ws.meId)
    .forEach((u) => map.set(u.dept, [...(map.get(u.dept) || []), u]))
  return [...map].map(([dept, people]) => ({ dept, people }))
})
const groupList = computed(() => ws.conversations.filter((c) => c.type === 'group'))
async function openContact(u) {
  const id = await ws.openDm(u.id)
  if (id) router.push(`/chat/${id}`)
}

const list = computed(() =>
  ws.conversations
    .filter((c) => (filter.value === 'unread' ? c.unread : filter.value === 'flag' ? c.agentFlag : true))
    .sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0)),
)

// 没指定会话时打开第一个会话（后端按置顶、最近消息排序）
const convId = computed(() => route.params.id || ws.conversations[0]?.id)
const conv = computed(() => ws.getConv(convId.value))
// 列表里高亮的会话：手机宽度下只显示列表、没打开任何会话时不高亮
const selectedId = computed(() => (ws.isCompact && !route.params.id ? null : convId.value))
const msgs = computed(() => ws.messages[convId.value] || [])
const summary = computed(() => ws.summaries[convId.value])
const replies = computed(() => ws.replies[convId.value] || [])
const summaryOpen = ref(true)
const summaryExpanded = ref(false)

const draft = ref('')
const draftFlash = ref(false)
const input = ref(null)
const body = ref(null)

function scrollBottom() {
  nextTick(() => body.value && (body.value.scrollTop = body.value.scrollHeight))
}

watch(
  convId,
  async (id) => {
    if (!id) return
    summaryOpen.value = true
    summaryExpanded.value = false
    draft.value = ''
    // 有缓存先显示，同时从后端刷新
    if (!route.query.msg && msgs.value.length) scrollBottom()
    ws.loadAssist(id)
    await ws.loadMessages(id)
    if (convId.value === id && !route.query.msg) scrollBottom()
    // 打开会话 1.2 秒后视为已读
    setTimeout(() => convId.value === id && ws.markRead(id), 1200)
  },
  { immediate: true },
)
watch(() => msgs.value.length, (n, o) => o !== undefined && scrollBottom())

// 告诉 store 正在看哪个会话：推送来的新消息直接算已读
watch(convId, (id) => (ws.openConvId = id), { immediate: true })
onBeforeUnmount(() => (ws.openConvId = null))
// 切回这个标签页时，把离开期间收到的消息标为已读
function markReadIfVisible() {
  if (convId.value && document.visibilityState === 'visible') ws.markRead(convId.value)
}
document.addEventListener('visibilitychange', markReadIfVisible)
onBeforeUnmount(() => document.removeEventListener('visibilitychange', markReadIfVisible))

// 从 TODO「查看原消息」跳来：滚动到原消息并高亮
const locatedId = ref(null)
let located = null
watch(
  () => [convId.value, route.query.msg, msgs.value.length],
  ([, q, count]) => {
    // 消息可能还在加载，加载完再定位；同一条只定位一次
    if (!q) return (located = null)
    const msgId = Number(q)
    if (!count || located === `${convId.value}:${msgId}`) return
    located = `${convId.value}:${msgId}`
    summaryOpen.value = false
    locatedId.value = msgId
    nextTick(() =>
      body.value?.querySelector(`[data-msg="${msgId}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }),
    )
    setTimeout(() => (locatedId.value = null), 2400)
  },
  { immediate: true, flush: 'post' },
)
// 这条消息被整理成了哪项 TODO
// 这条消息关联的 TODO（AI 不可用时不显示 AI 的待确认建议）
const todoOf = (m) =>
  ws.todosForMessage(convId.value, m.id).find((t) => agentStore.aiOk || t.status !== 'suggested')
const todoLabel = { suggested: `${agentStore.name}建议加入 TODO`, open: '已在 TODO', done: 'TODO 已完成' }
function toTodo(m) {
  ws.todoFromMessage(m.id)
}

// 接收 Agent 面板写过来的草稿
watch(
  () => ws.pendingDraft,
  (d) => {
    if (!d || d.convId !== convId.value) return
    draft.value = d.text
    ws.pendingDraft = null
    draftFlash.value = true
    setTimeout(() => (draftFlash.value = false), 1600)
    nextTick(() => input.value?.focus())
  },
  { immediate: true },
)

// —— 附件：点回形针选文件、拖进窗口、或在输入框里粘贴；选中即发送 ——
const MAX_FILE_BYTES = 50 * 1024 * 1024 // 与后端 spring.servlet.multipart.max-file-size 一致
const fileInput = ref(null)
const uploads = ref([]) // 正在上传 / 上传失败的文件：{ key, convId, file, progress, error, controller }
const convUploads = computed(() => uploads.value.filter((u) => u.convId === convId.value))
let uploadSeq = 0

function sendFiles(files) {
  for (const file of files) {
    if (file.size === 0) ws.notify(`「${file.name}」是空文件`)
    else if (file.size > MAX_FILE_BYTES) ws.notify(`「${file.name}」超过 50 MB，发不了`)
    else startUpload({ key: ++uploadSeq, convId: convId.value, file })
  }
}
function onFilesPicked(e) {
  sendFiles([...e.target.files])
  // 清空，同一个文件可以再选一次
  e.target.value = ''
}
async function startUpload(item) {
  if (!uploads.value.some((u) => u.key === item.key)) uploads.value.push(item)
  const u = uploads.value.find((x) => x.key === item.key)
  Object.assign(u, { progress: 0, error: '', controller: new AbortController() })
  scrollBottom()
  try {
    const msg = await api.sendFile(u.convId, u.file, {
      onProgress: (p) => (u.progress = p),
      signal: u.controller.signal,
    })
    removeUpload(u)
    ws.applySent(msg)
  } catch (e) {
    // 取消的直接移除；其他失败留着，可以重试
    if (e.status === -1) removeUpload(u)
    else u.error = e.message
  }
}
function removeUpload(u) {
  uploads.value = uploads.value.filter((x) => x.key !== u.key)
}
function cancelUpload(u) {
  u.controller?.abort()
  removeUpload(u)
}

// 拖文件进会话窗口
const dragging = ref(false)
let dragDepth = 0
const hasFiles = (e) => [...(e.dataTransfer?.types || [])].includes('Files')
function onDragEnter(e) {
  if (!hasFiles(e)) return
  dragDepth++
  dragging.value = true
}
function onDragLeave(e) {
  if (hasFiles(e) && --dragDepth <= 0) dragging.value = false
}
function onDrop(e) {
  dragDepth = 0
  dragging.value = false
  if (e.dataTransfer?.files.length) sendFiles([...e.dataTransfer.files])
}
// 粘贴截图 / 文件直接发送；截图默认叫 image.png，换成带时间的名字
function onPaste(e) {
  const files = [...(e.clipboardData?.files || [])]
  if (!files.length) return
  e.preventDefault()
  const pad = (n) => String(n).padStart(2, '0')
  const d = new Date()
  const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  sendFiles(files.map((f) => (f.name === 'image.png' ? new File([f], `截图-${stamp}.png`, { type: f.type }) : f)))
}

// —— 表情：在光标处插入，面板保持打开可以连续选 ——
const emojiOpen = ref(false)
const emojiEl = ref(null)
function insertEmoji(e) {
  input.value?.insert(e)
}

// —— @ 提及：输入 @（或点 @ 按钮）后弹出会话成员，边打字边筛选 ——
const mention = ref(null) // { start: @ 所在位置, query: @ 后已输入的字 }
const mentionIndex = ref(0)
const mentionPeople = computed(() => {
  if (!mention.value) return []
  const q = mention.value.query.toLowerCase()
  return (ws.members[convId.value] || [])
    .filter((u) => u.id !== ws.meId && (!q || u.name.toLowerCase().includes(q)))
    .slice(0, 20)
})
// 光标前是「@xxx」时打开；@ 前面是字母数字（如邮箱）不算
async function onCaret(el) {
  const before = el.value.slice(0, el.selectionStart)
  const m = before.match(/(?:^|[^A-Za-z0-9])@([^\s@]{0,20})$/)
  if (!m) return (mention.value = null)
  await ws.loadMembers(convId.value)
  const query = m[1]
  if (mention.value?.query !== query) mentionIndex.value = 0
  mention.value = { start: el.selectionStart - query.length - 1, query }
}
function pickMention(person) {
  input.value?.insert(`@${person.name} `, mention.value.start)
  mention.value = null
}
function startMention() {
  const el = input.value?.el
  const pos = el?.selectionStart ?? draft.value.length
  // 前面紧挨着字母数字时先补个空格，否则不会被识别成 @
  const needSpace = pos > 0 && /[A-Za-z0-9]/.test(draft.value[pos - 1])
  input.value?.insert(`${needSpace ? ' ' : ''}@`)
}
// 选人面板打开时接管上下键、回车 / Tab、Esc
function onComposerKey(e) {
  if (!mention.value || e.isComposing) return
  const n = mentionPeople.value.length
  if (e.key === 'ArrowDown' && n) {
    e.preventDefault()
    mentionIndex.value = (mentionIndex.value + 1) % n
  } else if (e.key === 'ArrowUp' && n) {
    e.preventDefault()
    mentionIndex.value = (mentionIndex.value - 1 + n) % n
  } else if ((e.key === 'Enter' || e.key === 'Tab') && n) {
    e.preventDefault()
    pickMention(mentionPeople.value[mentionIndex.value])
  } else if (e.key === 'Escape') {
    e.preventDefault()
    mention.value = null
  }
}

// 点面板外面关闭表情面板；换会话时收起所有面板
function onDocDown(e) {
  if (emojiOpen.value && !emojiEl.value?.contains(e.target)) emojiOpen.value = false
}
document.addEventListener('pointerdown', onDocDown)
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocDown))
watch(convId, () => {
  emojiOpen.value = false
  mention.value = null
})

async function send() {
  const text = draft.value
  draft.value = ''
  // 发送失败时把内容放回输入框
  if (!(await ws.sendMessage(convId.value, text))) draft.value = text
}

// 输入区浮在消息列表之上：测量其高度，给消息列表底部留出等高空间
const areaEl = ref(null)
const composerH = ref(110)
let ro
watch(areaEl, (el, old) => {
  ro ||= new ResizeObserver(([e]) => {
    const h = e.borderBoxSize?.[0]?.blockSize ?? e.target.offsetHeight
    const atBottom = body.value && body.value.scrollHeight - body.value.scrollTop - body.value.clientHeight < 40
    composerH.value = Math.ceil(h)
    if (atBottom) scrollBottom()
  })
  if (old) ro.unobserve(old)
  if (el) ro.observe(el)
})
// 顶部区域同样浮在消息之上：测量高度，给消息列表顶部留空
const topEl = ref(null)
const topH = ref(84)
let roTop
watch(topEl, (el, old) => {
  roTop ||= new ResizeObserver(([e]) => {
    topH.value = Math.ceil(e.borderBoxSize?.[0]?.blockSize ?? e.target.offsetHeight)
  })
  if (old) roTop.unobserve(old)
  if (el) roTop.observe(el)
})
onBeforeUnmount(() => {
  ro?.disconnect()
  roTop?.disconnect()
})

function askAgent(text) {
  ws.agentCollapsed = false
  agentStore.send(text, { view: 'chat', convId: convId.value, label: `消息 · ${ws.convTitle(conv.value)}` })
}
function nameOf(m) {
  return ws.users[m.from]?.name
}
</script>

<template>
  <div class="chat" :class="{ 'has-conv': route.params.id, compact: ws.isCompact }">
    <!-- 会话列表 -->
    <aside class="list">
      <nav class="list-tabs">
        <button v-for="t in listTabs" :key="t.key" :class="{ on: listTab === t.key }" @click="listTab = t.key">
          {{ t.label }}
        </button>
      </nav>

      <!-- 消息坞：会话列表 -->
      <template v-if="listTab === 'dock'">
        <div class="filters">
          <button v-for="f in filters" :key="f.key" :class="{ on: filter === f.key }" @click="filter = f.key">
            <Sparkles v-if="f.key === 'flag'" :size="12" />{{ f.label }}
          </button>
        </div>
        <div class="convs">
          <button
            v-for="c in list"
            :key="c.id"
            class="conv"
            :class="{ active: c.id === selectedId }"
            @click="router.push(`/chat/${c.id}`)"
          >
            <Avatar v-if="c.type === 'dm'" :user="c.userId" :size="42" />
            <Avatar v-else :name="c.name" :color="c.color" :size="42" square />
            <div class="conv-body">
              <div class="conv-top">
                <span class="conv-name">{{ ws.convTitle(c) }}</span>
                <Pin v-if="c.pinned" :size="12" class="muted" />
                <span class="conv-time">{{ c.time }}</span>
              </div>
              <div class="conv-bottom">
                <span v-if="agentStore.aiOk && c.agentFlag && c.unread" class="flag"><Sparkles :size="11" />{{ c.agentFlag }}</span>
                <span class="conv-last">{{ c.last }}</span>
                <BellOff v-if="c.muted" :size="12" class="muted" />
                <span v-if="c.unread" class="unread" :class="{ quiet: c.muted }">{{ c.unread }}</span>
              </div>
            </div>
          </button>
        </div>
      </template>

      <!-- 通讯录：按部门 -->
      <div v-else-if="listTab === 'contacts'" class="convs">
        <template v-for="g in depts" :key="g.dept">
          <div class="dept">{{ g.dept }} <span>{{ g.people.length }}</span></div>
          <button
            v-for="u in g.people"
            :key="u.id"
            class="conv"
            :class="{ active: selectedId && conv?.type === 'dm' && conv.userId === u.id }"
            @click="openContact(u)"
          >
            <Avatar :user="u.id" :size="38" />
            <div class="conv-body">
              <div class="conv-name">{{ u.name }}</div>
              <div class="conv-last">{{ u.role }}</div>
            </div>
          </button>
        </template>
      </div>

      <!-- 群组 -->
      <div v-else class="convs">
        <button
          v-for="c in groupList"
          :key="c.id"
          class="conv"
          :class="{ active: c.id === selectedId }"
          @click="router.push(`/chat/${c.id}`)"
        >
          <Avatar :name="c.name" :color="c.color" :size="38" square />
          <div class="conv-body">
            <div class="conv-name">{{ c.name }}</div>
            <div class="conv-last">{{ c.members }} 人</div>
          </div>
        </button>
      </div>
    </aside>

    <!-- 会话窗口 -->
    <section
      v-if="conv"
      class="window"
      :style="{ '--composer-h': composerH + 'px', '--top-h': topH + 'px' }"
      @dragenter.prevent="onDragEnter"
      @dragover.prevent
      @dragleave="onDragLeave"
      @drop.prevent="onDrop"
    >
      <!-- 顶部区域浮在消息之上，下边缘渐进透明，消息可从下方滚过 -->
      <div ref="topEl" class="top-area">
      <header class="win-head">
        <button class="icon-btn ghost back" title="返回会话列表" @click="router.push('/chat')"><ChevronLeft :size="20" /></button>
        <div class="win-title">
          <h2>{{ ws.convTitle(conv) }}</h2>
          <span class="muted">{{ conv.type === 'group' ? `${conv.members} 人` : ws.users[conv.userId]?.role }}</span>
        </div>
        <div class="win-actions">
          <!-- 手机宽度下顶部栏隐藏，小雀从这里打开 -->
          <button
            v-if="ws.isCompact && agentStore.aiOk"
            class="icon-btn ghost orb-btn"
            :title="agentStore.name"
            :aria-label="`打开${agentStore.name}`"
            @click="ws.agentCollapsed = false"
          >
            <AgentOrb :size="26" />
          </button>
          <button class="icon-btn ghost desk-only"><Phone :size="18" /></button>
          <button class="icon-btn ghost desk-only"><Video :size="18" /></button>
          <button v-if="conv.type === 'group'" class="icon-btn ghost desk-only"><Users :size="18" /></button>
          <button class="icon-btn ghost"><MoreHorizontal :size="18" /></button>
        </div>
      </header>

      <!-- 小雀未读摘要：一行提示，点开看要点 -->
      <div v-if="agentStore.aiOk && summary && summaryOpen" class="ai-tip">
        <button class="tip-row" @click="summaryExpanded = !summaryExpanded">
          <AgentOrb :size="20" />
          <span class="tip-text">{{ agentStore.name }}：离线期间 {{ summary.count }} 条消息的要点</span>
          <ChevronDown :size="14" class="chev" :class="{ up: summaryExpanded }" />
        </button>
        <button class="icon-btn ghost sm" title="关闭" @click="summaryOpen = false"><X :size="14" /></button>
        <div v-if="summaryExpanded" class="tip-body">
          <ol>
            <li v-for="p in summary.points" :key="p">{{ p }}</li>
          </ol>
          <button class="tip-link" @click="askAgent('帮我起草回复')"><WandSparkles :size="13" /> 起草回复</button>
        </div>
      </div>
      </div>

      <div ref="body" class="msgs">
        <template v-for="(m, i) in msgs" :key="m.id">
        <!-- 跨天时插一条日期分隔 -->
        <div v-if="i === 0 || dayLabel(m.sentAt) !== dayLabel(msgs[i - 1].sentAt)" class="day">
          {{ dayLabel(m.sentAt) }}
        </div>
        <div
          :data-msg="m.id"
          class="msg"
          :class="{ mine: m.from === ws.meId, mention: m.mention, located: locatedId === m.id }"
        >
          <Avatar :user="m.from" :size="36" />
          <div class="msg-main">
            <div class="msg-meta">
              <span v-if="m.from !== ws.meId">{{ nameOf(m) }}</span>
              <span class="muted">{{ m.time }}</span>
              <span v-if="m.mention" class="at-me">@你</span>
              <button
                v-if="todoOf(m)"
                class="todo-mark"
                :class="todoOf(m).status"
                :title="`${todoLabel[todoOf(m).status]}：${todoOf(m).title}`"
                @click="router.push({ path: '/todo', query: { focus: todoOf(m).id } })"
              >
                <CircleCheck v-if="todoOf(m).status === 'done'" :size="13" />
                <Sparkles v-else-if="todoOf(m).status === 'suggested'" :size="13" />
                <ListTodo v-else :size="13" />
              </button>
              <button v-else-if="m.from !== ws.meId" class="to-todo" title="转为 TODO" @click="toTodo(m)">
                <ListPlus :size="13" /> 转为 TODO
              </button>
            </div>
            <a v-if="m.file?.image" class="image" :href="fileUrl(m.id)" target="_blank" rel="noopener" :title="m.file.name">
              <img :src="fileUrl(m.id)" :alt="m.file.name" loading="lazy" />
            </a>
            <div v-else-if="m.file" class="file" :class="{ unavailable: !m.file.available }">
              <span class="file-ic"><FileText :size="22" /></span>
              <span class="file-body">
                <span class="file-name">{{ m.file.name }}</span>
                <span class="muted">{{ m.file.size }}</span>
              </span>
              <a v-if="m.file.available" class="icon-btn ghost sm" :href="fileUrl(m.id, true)" download title="下载">
                <Download :size="17" />
              </a>
              <Download v-else :size="17" class="muted" title="演示文件，没有内容可下载" />
            </div>
            <div v-else class="text"><MessageText :text="m.text" /></div>
          </div>
        </div>
        </template>

        <!-- 正在上传 / 上传失败的文件 -->
        <div v-for="u in convUploads" :key="u.key" class="msg mine uploading">
          <Avatar :user="ws.meId" :size="36" />
          <div class="msg-main">
            <div class="file" :class="{ failed: u.error }">
              <span class="file-ic"><FileText :size="22" /></span>
              <span class="file-body">
                <span class="file-name">{{ u.file.name }}</span>
                <span v-if="u.error" class="upload-error">{{ u.error }}</span>
                <span v-else class="progress"><i :style="{ width: `${Math.round(u.progress * 100)}%` }" /></span>
              </span>
              <button v-if="u.error" class="icon-btn ghost sm" title="重试" @click="startUpload(u)"><RotateCcw :size="15" /></button>
              <button class="icon-btn ghost sm" :title="u.error ? '移除' : '取消'" @click="cancelUpload(u)"><X :size="15" /></button>
            </div>
            <span v-if="!u.error" class="muted upload-meta">{{ fileSize(u.file.size) }} · {{ Math.round(u.progress * 100) }}%</span>
          </div>
        </div>
      </div>

      <!-- 拖文件进来时的提示 -->
      <div v-if="dragging" class="drop-hint">
        <Upload :size="28" />
        <span>松开发送给「{{ ws.convTitle(conv) }}」</span>
      </div>

      <footer ref="areaEl" class="composer-area">
        <!-- 建议回复：在输入框上方 -->
        <div v-if="agentStore.aiOk && replies.length && !draft" class="replies">
          <span class="rep-label"><Sparkles :size="12" /> 建议回复</span>
          <button v-for="r in replies" :key="r" class="rep" @click="draft = r">{{ r }}</button>
        </div>

        <div class="composer-wrap">
        <!-- 表情 / @ 选人面板：浮在输入框上方 -->
        <div v-if="emojiOpen" ref="emojiEl" class="popover">
          <EmojiPicker @pick="insertEmoji" />
        </div>
        <div v-else-if="mention" class="popover">
          <MentionPicker :people="mentionPeople" :active="mentionIndex" @pick="pickMention" @hover="mentionIndex = $event" />
        </div>

        <!-- 胶囊输入框：单行 / 多行（内容在上、按钮在下） -->
        <PillComposer
          ref="input"
          v-model="draft"
          :placeholder="`发送给 ${ws.convTitle(conv)}`"
          :can-send="!!draft.trim()"
          :flash="draftFlash"
          @submit="send"
          @keydown="onComposerKey"
          @caret="onCaret"
          @paste="onPaste"
          @blur="mention = null"
        >
          <template #left>
            <button class="icon-btn ghost tool" title="发送文件（也可以拖进窗口或粘贴）" @click="fileInput.click()">
              <Paperclip :size="18" />
            </button>
            <input ref="fileInput" type="file" multiple hidden @change="onFilesPicked" />
            <button
              class="icon-btn ghost tool"
              :class="{ on: emojiOpen }"
              title="表情"
              @pointerdown.stop
              @mousedown.prevent
              @click="emojiOpen = !emojiOpen"
            >
              <Smile :size="18" />
            </button>
            <button class="icon-btn ghost tool at" title="@ 提及" @mousedown.prevent @click="startMention">
              <AtSign :size="18" />
            </button>
          </template>
          <template v-if="agentStore.aiOk" #right>
            <button class="ai-write" :title="`${agentStore.name}帮写`" @click="askAgent('帮我起草回复')">
              <WandSparkles :size="14" /><span class="ai-label">{{ agentStore.name }}帮写</span>
            </button>
          </template>
        </PillComposer>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.chat {
  height: 100%;
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 12px;
  padding: 0 16px 16px;
}

.list {
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 12px 8px 8px;
  border-radius: var(--r-lg);
  background: var(--card-glass);
  box-shadow: inset 0 0 0 0.5px var(--edge-hi);
}
.list-tabs {
  display: flex;
  gap: 2px;
  margin: 0 4px;
  padding: 3px;
  border-radius: 999px;
  background: var(--seg-track);
  box-shadow: inset 0 0 0 1px var(--seg-border), var(--seg-shadow);
}
.list-tabs button {
  flex: 1;
  height: 28px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
  white-space: nowrap;
}
.list-tabs button:hover {
  color: var(--text-1);
}
.list-tabs button.on {
  background: var(--seg-active);
  color: var(--text-1);
  font-weight: 600;
  box-shadow: var(--seg-active-shadow);
}
.dept {
  padding: 12px 10px 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
}
.dept span {
  font-weight: 400;
}
.filters {
  display: flex;
  gap: 4px;
  padding: 10px 4px 8px;
}
.filters button {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 28px;
  padding: 0 11px;
  border-radius: 999px;
  font-size: 13px;
  color: var(--text-2);
}
.filters button:hover {
  background: var(--hover);
}
.filters button.on {
  background: var(--ink);
  color: var(--on-ink);
}
.convs {
  flex: 1;
  overflow-y: auto;
}
.conv {
  width: 100%;
  display: flex;
  gap: 10px;
  padding: 10px;
  border-radius: 14px;
  text-align: left;
  transition: background 0.15s;
}
.conv:hover {
  background: var(--list-hover);
}
.conv.active {
  background: var(--list-active);
}
.conv.active:hover {
  background: var(--list-active-hover);
}
.conv:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}
.conv-body {
  flex: 1;
  min-width: 0;
}
.conv-top,
.conv-bottom {
  display: flex;
  align-items: center;
  gap: 5px;
}
.conv-name {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.conv-time {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-3);
  flex: none;
}
.conv-last {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.flag {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 0 5px;
  height: 18px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 600;
  background: var(--accent-soft);
  color: var(--accent-strong);
}
.unread {
  flex: none;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
}
.unread.quiet {
  background: var(--fill-strong);
}

.window {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}
.top-area {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 2;
  /* 内容区不透明，下边缘多出 24px 渐进透明 */
  padding-bottom: 24px;
  background: linear-gradient(
    to bottom,
    var(--card) 0,
    var(--card) calc(100% - 24px),
    color-mix(in srgb, var(--card) 65%, transparent) calc(100% - 12px),
    transparent 100%
  );
  pointer-events: none;
}
.top-area > * {
  pointer-events: auto;
}
.win-head {
  flex: none;
  display: flex;
  align-items: center;
  height: 60px;
  padding: 0 12px 0 22px;
}
.win-title {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.win-title h2 {
  margin: 0;
  font-size: 17px;
}
.win-title span {
  flex: none;
  font-size: 13px;
  white-space: nowrap;
}
.win-actions {
  display: flex;
  gap: 2px;
}
.icon-btn.sm {
  width: 30px;
  height: 30px;
}


.ai-tip {
  flex: none;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  margin: 10px 16px 0;
  padding: 4px 4px 4px 6px;
  border-radius: 14px;
  background: var(--accent-softer);
}
.tip-row {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  font-size: 13px;
  color: var(--text-2);
  text-align: left;
}
.tip-text {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.chev {
  flex: none;
  color: var(--text-3);
  transition: transform 0.2s;
}
.chev.up {
  transform: rotate(180deg);
}
.tip-body {
  flex-basis: 100%;
  padding: 2px 8px 8px 34px;
  font-size: 13px;
}
.tip-body ol {
  margin: 0 0 6px;
  padding-left: 16px;
  line-height: 1.8;
}
.tip-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-strong);
}
.msgs {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* 顶部 / 底部分别留出浮动区域的高度，消息可从两者下方滚过 */
  padding: calc(var(--top-h) - 8px) 22px calc(var(--composer-h) + 12px);
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.day {
  align-self: center;
  font-size: 12px;
  color: var(--text-3);
}
.msg {
  display: flex;
  gap: 10px;
  max-width: 76%;
}
.msg.mine {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.msg-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.mine .msg-main {
  align-items: flex-end;
}
.msg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 3px;
  font-size: 12px;
  color: var(--text-2);
}
.text {
  padding: 9px 14px;
  border-radius: 4px 16px 16px 16px;
  background: var(--bubble);
  font-size: 15px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.mine .text {
  border-radius: 16px 4px 16px 16px;
  background: var(--bubble-mine);
}
.mention .text {
  background: var(--bubble-mention);
  box-shadow: inset 3px 0 0 var(--warn);
}
.msg.located .text,
.msg.located .file {
  animation: located 2.2s ease-out;
}
@keyframes located {
  0%,
  35% {
    box-shadow: 0 0 0 2px var(--accent), 0 0 0 7px var(--accent-soft);
  }
}
.todo-mark {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  color: var(--accent-strong);
  background: var(--accent-softer);
}
.todo-mark.done {
  color: var(--text-3);
  background: var(--card-sub);
}
.to-todo {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 20px;
  padding: 0 6px;
  border-radius: 6px;
  font-size: 11px;
  color: var(--text-3);
  opacity: 0;
  transition: opacity 0.15s;
}
.msg:hover .to-todo,
.to-todo:focus-visible {
  opacity: 1;
}
.to-todo:hover {
  background: var(--hover);
  color: var(--text-1);
}
.at-me {
  padding: 0 5px;
  border-radius: 4px;
  font-weight: 600;
  background: var(--warn-soft);
  color: var(--warn-text);
}
.file {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 300px;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--card);
  box-shadow: 0 0 0 0.5px var(--line-strong), var(--shadow-sm);
}
.file.unavailable {
  opacity: 0.8;
}
.file .icon-btn.sm {
  width: 30px;
  height: 30px;
  flex: none;
  color: var(--text-2);
}
.file.failed {
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--danger) 45%, transparent), var(--shadow-sm);
}
.progress {
  display: block;
  height: 4px;
  margin-top: 6px;
  border-radius: 2px;
  background: var(--fill-muted);
  overflow: hidden;
}
.progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  transition: width 0.2s;
}
.upload-error {
  font-size: 12px;
  color: var(--danger);
}
.upload-meta {
  font-size: 12px;
}
.image {
  display: block;
  max-width: min(260px, 100%);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 0 0 0.5px var(--line-strong), var(--shadow-sm);
  line-height: 0;
}
.image img {
  display: block;
  max-width: 100%;
  max-height: 260px;
  object-fit: cover;
}
.drop-hint {
  position: absolute;
  inset: 8px;
  z-index: 5;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-radius: calc(var(--r-lg) - 4px);
  background: color-mix(in srgb, var(--card) 88%, transparent);
  box-shadow: inset 0 0 0 2px var(--accent);
  color: var(--accent-strong);
  font-size: 14px;
  font-weight: 600;
  /* 拖拽事件交给下面的会话窗口处理 */
  pointer-events: none;
}
.composer-wrap {
  position: relative;
  pointer-events: auto;
}
.popover {
  position: absolute;
  left: 4px;
  bottom: calc(100% + 8px);
  z-index: 3;
  animation: pop-up 0.2s var(--ease-spring);
}
@keyframes pop-up {
  from {
    opacity: 0;
    transform: translateY(6px) scale(0.98);
  }
}
.tool.on {
  background: var(--hover-strong);
  color: var(--text-1);
}
.file-ic {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--pink-soft);
  color: var(--pink);
}
.file-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  font-size: 12px;
}
.file-name {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.composer-area {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 12px 12px;
  background: none;
  /* 容器透明且不拦截点击，只有其中的控件可交互 */
  pointer-events: none;
}
.replies {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 4px;
  overflow-x: auto;
  scrollbar-width: none;
  /* 自己能接收触摸，按钮之间的空隙也能横向滑动；右边缘渐隐提示还能往右滑 */
  pointer-events: auto;
  overscroll-behavior-x: contain;
  -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 28px), transparent);
  mask-image: linear-gradient(to right, #000 calc(100% - 28px), transparent);
}
.rep-label,
.rep {
  pointer-events: auto;
}
/* 毛玻璃：让下方滚过的消息隐约可见 */
.rep-label,
.rep {
  background: var(--card);
}
.rep-label {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 30px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--accent-strong);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}
.rep {
  flex: none;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 13px;
  color: var(--text-1);
  box-shadow: inset 0 0 0 1px rgba(18, 181, 160, 0.3), var(--shadow-sm);
  transition: background 0.15s;
}
.rep:hover {
  background: color-mix(in srgb, var(--accent) 16%, var(--card));
}

.tool {
  flex: none;
  width: 36px;
  height: 36px;
}
.ai-write {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(120deg, var(--blue), var(--accent));
}

.back {
  display: none;
  margin: 0 4px 0 -12px;
}

/* —— 手机宽度：会话全屏，像手机 IM 一样 —— */
.chat.compact.has-conv {
  padding: 0;
}
.chat.compact .window {
  border-radius: 0;
  box-shadow: none;
}
.compact .win-head {
  height: 56px;
  padding: 0 6px 0 4px;
}
.compact .back {
  margin: 0 2px 0 0;
}
/* 名字单独一行，人数 / 职位放在下面，不再和名字抢宽度 */
.compact .win-title {
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
  line-height: 1.3;
}
.compact .win-title h2 {
  max-width: 100%;
  font-size: 16px;
}
.compact .win-title span {
  font-size: 12px;
}
.compact .desk-only {
  display: none;
}
.orb-btn {
  display: grid;
  place-items: center;
}
.compact .composer-area {
  /* 给 iPhone 底部的横条留出位置 */
  padding-bottom: calc(8px + env(safe-area-inset-bottom));
}
.compact .rep {
  height: 32px;
}

/* —— 响应式：按主窗口宽度 —— */
@container ws (max-width: 860px) {
  .chat {
    grid-template-columns: 250px minmax(0, 1fr);
  }
}
/* 窄：列表与会话二选一 */
@container ws (max-width: 680px) {
  .chat {
    grid-template-columns: minmax(0, 1fr);
    padding: 0 10px 10px;
  }
  .chat.has-conv .list,
  .chat:not(.has-conv) .window {
    display: none;
  }
  .back {
    display: inline-grid;
  }
  .win-head {
    padding: 0 8px 0 18px;
  }
  .win-title {
    min-width: 0;
  }
  .win-title h2 {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ai-tip {
    margin: 10px 10px 0;
  }
  .msgs {
    padding: calc(var(--top-h) - 8px) 14px calc(var(--composer-h) + 12px);
  }
  .msg {
    max-width: 90%;
  }
  .file {
    width: 240px;
  }
  .composer-area {
    padding: 0 8px 8px;
  }
  .at {
    display: none;
  }
  .ai-write {
    width: 36px;
    padding: 0;
    justify-content: center;
  }
  .ai-label {
    display: none;
  }
}
</style>
