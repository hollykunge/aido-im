<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Search, X, FileText, ListTodo, CircleCheck, Sparkles, ChevronRight, Command } from 'lucide-vue-next'
import Avatar from '@/components/common/Avatar.vue'
import AgentOrb from '@/components/common/AgentOrb.vue'
import HighlightText from '@/components/common/HighlightText.vue'
import { useWorkspace } from '@/stores/workspace'
import { useAgent } from '@/stores/agent'
import { api, toTodo } from '@/api'
import { fileSize, msgTime } from '@/api/format'

const router = useRouter()
const ws = useWorkspace()
const agentStore = useAgent()

const q = ref('')
const input = ref(null)
// 搜索框 + 类型筛选浮在结果之上：测量高度，给结果顶部留空
const headEl = ref(null)
const headH = ref(64)
let roHead
const kw = computed(() => q.value.trim())

const recent = ref([])
onMounted(async () => (recent.value = (await api.searchHistory().catch(() => null)) || recent.value))
async function remember() {
  const t = q.value.trim()
  if (!t) return
  recent.value = [t, ...recent.value.filter((r) => r !== t)].slice(0, 6)
  api.rememberSearch(t).catch(() => {})
}

// —— 搜索：输入停顿 200ms 后请求后端；只采用最后一次请求的结果 ——
const LABELS = { contacts: '联系人', groups: '群组', messages: '消息', files: '文件', todos: 'TODO' }
const hitOf = (h) => ({
  conv: { id: h.conversationId, name: h.conversationTitle },
  m: {
    id: h.messageId, from: h.senderId, fromName: h.senderName, text: h.text,
    file: h.fileName && { name: h.fileName, size: fileSize(h.fileSize) }, time: msgTime(h.sentAt),
  },
})
const CONVERT = {
  contacts: (u) => u,
  groups: (g) => ({ id: g.id, name: g.title, color: g.color, members: g.memberCount }),
  messages: hitOf,
  files: hitOf,
  todos: toTodo,
}
const result = ref(null)
let seq = 0
let timer
watch([kw, () => agentStore.aiOk], ([k]) => {
  clearTimeout(timer)
  if (!k) return (result.value = null)
  timer = setTimeout(async () => {
    const my = ++seq
    // AI 不可用时不搜小雀的待确认建议
    const r = await ws.attempt(() => api.search(k, agentStore.aiOk))
    if (my === seq && r) result.value = r
  }, 200)
})

const sections = computed(() =>
  Object.entries(LABELS).map(([key, label]) => {
    const sec = result.value?.[key] || { total: 0, items: [] }
    return { key, label, total: sec.total, items: sec.items.map(CONVERT[key]) }
  }),
)
const total = computed(() => result.value?.total || 0)
// 手机宽度放不下全部类型：只显示有结果的，没结果的类型点了也没用
const scopeTabs = computed(() => (ws.isCompact ? sections.value.filter((s) => s.total) : sections.value))

// 手机上按键盘的「搜索」后收起键盘，把结果露出来
function onEnter(e) {
  remember()
  if (ws.isCompact) e.target.blur()
}
async function clearRecent() {
  const before = recent.value
  recent.value = []
  if ((await ws.attempt(() => api.clearSearchHistory().then(() => true))) === null) recent.value = before
}

// 范围：全部时每类最多 3 条
const scope = ref('all')
const shown = computed(() =>
  sections.value
    .filter((s) => s.total && (scope.value === 'all' || scope.value === s.key))
    .map((s) => ({ ...s, list: scope.value === 'all' ? s.items.slice(0, 3) : s.items })),
)

// —— 跳转 ——
function go(to) {
  remember()
  router.push(to)
}
async function openContact(u) {
  const id = await ws.openDm(u.id)
  if (id) go(`/chat/${id}`)
}
const openGroup = (c) => go(`/chat/${c.id}`)
const openMessage = ({ conv, m }) => go({ path: `/chat/${conv.id}`, query: { msg: m.id } })
const openTodo = (t) => go({ path: '/todo', query: { focus: t.id } })
function askAgent() {
  remember()
  ws.agentCollapsed = false
  agentStore.send(q.value, { view: 'search', label: '搜索' })
}
function clear() {
  q.value = ''
  scope.value = 'all'
  input.value?.focus()
}

// 进入页面或按 ⌘K 时聚焦搜索框
const focus = () => nextTick(() => input.value?.focus())
onMounted(() => {
  roHead = new ResizeObserver(([e]) => (headH.value = Math.ceil(e.borderBoxSize?.[0]?.blockSize ?? e.target.offsetHeight)))
  roHead.observe(headEl.value)
  focus()
  window.addEventListener('yunque:focus-search', focus)
})
onBeforeUnmount(() => {
  window.removeEventListener('yunque:focus-search', focus)
  roHead?.disconnect()
})
</script>

<template>
  <div class="search-page" :style="{ '--head-h': headH + 'px' }">
    <!-- 搜索框和类型筛选浮在结果之上；结果在其下方渐进透明，可从下面滚过 -->
    <header ref="headEl" class="head">
      <div class="head-inner">
    <div class="box">
      <Search :size="18" class="muted" />
      <!-- type=search + enterkeyhint：手机键盘的回车键显示为「搜索」 -->
      <input
        ref="input"
        v-model="q"
        type="search"
        enterkeyhint="search"
        autocomplete="off"
        :placeholder="ws.isCompact ? '搜索人、群组、消息、文件' : '搜索联系人、群组、消息、文件和 TODO'"
        @keydown.enter="onEnter"
        @keydown.esc="clear"
      />
      <button v-if="q" class="icon-btn ghost sm" title="清空" @click="clear"><X :size="15" /></button>
      <kbd v-else-if="!ws.isCompact"><Command :size="11" />K</kbd>
    </div>

      <nav v-if="kw" class="scopes">
        <button :class="{ on: scope === 'all' }" @click="scope = 'all'">全部 <i>{{ total }}</i></button>
        <button
          v-for="s in scopeTabs"
          :key="s.key"
          :class="{ on: scope === s.key }"
          :disabled="!s.total"
          @click="scope = s.key"
        >
          {{ s.label }} <i>{{ s.total }}</i>
        </button>
      </nav>
      </div>
    </header>

    <div class="page">
    <!-- 未输入：最近搜索 -->
    <div v-if="!kw && recent.length" class="recent">
      <span class="muted">最近搜索</span>
      <button v-for="r in recent" :key="r" class="chip" @click="q = r">{{ r }}</button>
      <button class="clear-recent" title="清空最近搜索" @click="clearRecent">清空</button>
    </div>

    <template v-else>
      <!-- AI 可用时：把问题交给小雀 -->
      <button v-if="agentStore.aiOk && scope === 'all'" class="row ask" @click="askAgent">
        <AgentOrb :size="28" />
        <span class="row-main">
          <span class="row-title">问{{ agentStore.name }}：{{ q.trim() }}</span>
        </span>
        <ChevronRight :size="16" class="muted" />
      </button>

      <section v-for="s in shown" :key="s.key" class="section">
        <header>
          <h2>{{ s.label }}</h2>
          <button v-if="scope === 'all' && s.total > 3" class="more" @click="scope = s.key">
            查看全部 {{ s.total }}
          </button>
        </header>

        <template v-if="s.key === 'contacts'">
          <button v-for="u in s.list" :key="u.id" class="row" @click="openContact(u)">
            <Avatar :user="u.id" :size="34" />
            <span class="row-main">
              <span class="row-title"><HighlightText :text="u.name" :q="q" /></span>
              <span class="row-sub"><HighlightText :text="`${u.dept} · ${u.role}`" :q="q" /></span>
            </span>
          </button>
        </template>

        <template v-else-if="s.key === 'groups'">
          <button v-for="c in s.list" :key="c.id" class="row" @click="openGroup(c)">
            <Avatar :name="c.name" :color="c.color" :size="34" square />
            <span class="row-main">
              <span class="row-title"><HighlightText :text="c.name" :q="q" /></span>
              <span class="row-sub">{{ c.members }} 人</span>
            </span>
          </button>
        </template>

        <template v-else-if="s.key === 'messages'">
          <button v-for="r in s.list" :key="r.conv.id + r.m.id" class="row" @click="openMessage(r)">
            <Avatar :user="r.m.from" :size="34" />
            <span class="row-main">
              <span class="row-title small">
                {{ r.m.fromName }} <span class="muted">· {{ ws.convTitle(r.conv) }} · {{ r.m.time }}</span>
              </span>
              <span class="row-snippet"><HighlightText :text="r.m.text" :q="q" /></span>
            </span>
          </button>
        </template>

        <template v-else-if="s.key === 'files'">
          <button v-for="r in s.list" :key="r.conv.id + r.m.id" class="row" @click="openMessage(r)">
            <span class="file-ic"><FileText :size="18" /></span>
            <span class="row-main">
              <span class="row-title"><HighlightText :text="r.m.file.name" :q="q" /></span>
              <span class="row-sub">{{ r.m.fromName }} · {{ ws.convTitle(r.conv) }} · {{ r.m.file.size }}</span>
            </span>
          </button>
        </template>

        <template v-else>
          <button v-for="t in s.list" :key="t.id" class="row" @click="openTodo(t)">
            <span class="todo-ic" :class="t.status">
              <CircleCheck v-if="t.status === 'done'" :size="18" />
              <Sparkles v-else-if="t.status === 'suggested'" :size="16" />
              <ListTodo v-else :size="18" />
            </span>
            <span class="row-main">
              <span class="row-title" :class="{ done: t.status === 'done' }"><HighlightText :text="t.title" :q="q" /></span>
              <span class="row-sub">{{ t.status === 'done' ? '已完成' : t.due }}</span>
            </span>
          </button>
        </template>
      </section>

      <div v-if="!total" class="empty">没有找到「{{ q.trim() }}」相关内容</div>
    </template>
    </div>
  </div>
</template>

<style scoped>
.search-page {
  position: relative;
  height: 100%;
}
.head {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 3;
  padding: 4px 24px 0;
  pointer-events: none;
}
.head-inner {
  max-width: 760px;
  margin: 0 auto;
  pointer-events: auto;
}
.page {
  height: 100%;
  overflow-y: auto;
  padding: calc(var(--head-h) + 4px) 24px 40px;
  /* 浮动头部区域内结果完全透明，下方 24px 渐进显现 */
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, transparent calc(var(--head-h) - 6px), #000 calc(var(--head-h) + 18px));
  mask-image: linear-gradient(to bottom, transparent 0, transparent calc(var(--head-h) - 6px), #000 calc(var(--head-h) + 18px));
}
.page > * {
  max-width: 760px;
  margin-left: auto;
  margin-right: auto;
}
.box {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 52px;
  padding: 0 10px 0 18px;
  border-radius: 26px;
  background: var(--card);
  box-shadow: var(--shadow-md), 0 0 0 0.5px var(--line-strong);
}
.box:focus-within {
  box-shadow: var(--shadow-md), 0 0 0 1.5px rgba(18, 181, 160, 0.5);
}
.box input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  font-size: 16px;
}
.box input::placeholder {
  color: var(--text-3);
}
/* 用自己的清空按钮，去掉浏览器给 type=search 加的叉号和样式 */
.box input {
  -webkit-appearance: none;
  appearance: none;
}
.box input::-webkit-search-cancel-button,
.box input::-webkit-search-decoration {
  -webkit-appearance: none;
  display: none;
}
.clear-recent {
  height: 30px;
  padding: 0 8px;
  font-size: 13px;
  color: var(--text-3);
}
.clear-recent:hover {
  color: var(--text-1);
}
kbd {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px 7px;
  border-radius: 6px;
  font-family: inherit;
  font-size: 11px;
  color: var(--text-3);
  box-shadow: inset 0 0 0 1px var(--line-strong);
}
.icon-btn.sm {
  width: 30px;
  height: 30px;
}

.recent {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 16px 6px;
  font-size: 13px;
}
.chip {
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 13px;
  color: var(--text-2);
  background: var(--glass-2);
  box-shadow: 0 0 0 0.5px var(--line-strong);
}
.chip:hover {
  color: var(--text-1);
}

.scopes {
  display: flex;
  gap: 4px;
  padding: 14px 2px 10px;
  overflow-x: auto;
  scrollbar-width: none;
  overscroll-behavior-x: contain;
  /* 放不下时右边缘渐隐，提示可以横向滑动 */
  -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 24px), transparent);
  mask-image: linear-gradient(to right, #000 calc(100% - 24px), transparent);
}
.scopes button {
  flex: none;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 13px;
  color: var(--text-2);
}
.scopes button:hover:not(:disabled) {
  background: var(--hover);
}
.scopes button.on {
  background: var(--ink);
  color: var(--on-ink);
}
.scopes button:disabled {
  opacity: 0.4;
  cursor: default;
}
.scopes i {
  font-style: normal;
  font-size: 11px;
  opacity: 0.6;
}

.section {
  margin-top: 10px;
  padding: 10px 8px;
  border-radius: 18px;
  background: var(--panel);
  box-shadow: var(--shadow-sm), inset 0 0 0 0.5px var(--edge-hi);
}
.section header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px 4px;
}
h2 {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
}
.more {
  font-size: 12px;
  color: var(--accent-strong);
}
.row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  border-radius: 12px;
  text-align: left;
}
.row:hover {
  background: var(--list-hover);
}
.row.ask {
  margin-top: 4px;
  padding: 10px 12px;
  background: var(--accent-softer);
}
.row.ask:hover {
  background: var(--accent-soft);
}
.row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.row-title {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row-title.small {
  font-size: 12px;
  font-weight: 500;
}
.row-title.done {
  text-decoration: line-through;
  color: var(--text-3);
}
.row-sub {
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row-snippet {
  font-size: 13px;
  color: var(--text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.file-ic,
.todo-ic {
  flex: none;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 10px;
}
.file-ic {
  background: var(--pink-soft);
  color: var(--pink);
}
.todo-ic {
  background: var(--accent-softer);
  color: var(--accent-strong);
}
.todo-ic.done {
  background: var(--card-sub);
  color: var(--text-3);
}
.empty {
  padding: 40px 0;
  text-align: center;
  font-size: 14px;
  color: var(--text-3);
}

@container ws (max-width: 560px) {
  .page {
    padding: calc(var(--head-h) + 4px) 10px 24px;
  }
  .head {
    padding: 4px 10px 0;
  }
  kbd {
    display: none;
  }
}
</style>
