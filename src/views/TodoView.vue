<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Sparkles, RefreshCw, ChevronDown, Plus } from 'lucide-vue-next'
import TodoItem from '@/components/todo/TodoItem.vue'
import { useWorkspace } from '@/stores/workspace'
import { useAgent } from '@/stores/agent'

const route = useRoute()
const ws = useWorkspace()
const agentStore = useAgent()

const live = computed(() => ws.todos.filter((t) => t.status !== 'dismissed'))
// 待确认是 AI 的建议，AI 不可用时不显示
const suggested = computed(() => (agentStore.aiOk ? live.value.filter((t) => t.status === 'suggested') : []))
const today = computed(() => live.value.filter((t) => t.status === 'open' && t.today))
const later = computed(() => live.value.filter((t) => t.status === 'open' && !t.today))
const done = computed(() => live.value.filter((t) => t.status === 'done'))
const showDone = ref(false)

// 手动新建：一行输入框，回车保存
const adding = ref(false)
const newTitle = ref('')
const newInput = ref(null)
const pageEl = ref(null)
function startAdd() {
  // 手机上从右下角的按钮点进来：先滚到顶部，输入框出现在那里
  pageEl.value?.scrollTo({ top: 0, behavior: 'smooth' })
  adding.value = true
  nextTick(() => newInput.value?.focus())
}
async function saveNew() {
  if (!newTitle.value.trim()) return (adding.value = false)
  if (await ws.addTodo(newTitle.value)) newTitle.value = ''
}
function cancelAdd() {
  newTitle.value = ''
  adding.value = false
}

// 从聊天或 Agent 跳来时定位到某一项
const focusId = ref(null)
watch(
  () => route.query.focus,
  (q) => {
    if (!q) return
    const id = Number(q)
    const t = ws.getTodo(id)
    if (t?.status === 'done') showDone.value = true
    focusId.value = id
    nextTick(() => document.getElementById(`todo-${id}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }))
    setTimeout(() => (focusId.value = null), 2400)
  },
  { immediate: true },
)

function rescan() {
  ws.agentCollapsed = false
  agentStore.send('整理一下消息里的待办', { view: 'todo', label: 'TODO' })
}
</script>

<template>
  <div class="todo-page" :class="{ compact: ws.isCompact }">
    <!-- 标题栏浮在列表之上；列表在其下方渐进透明，可从下面滚过 -->
    <header v-if="!ws.isCompact" class="head">
      <div class="head-inner">
      <h1>TODO</h1>
      <button
        v-if="agentStore.aiOk"
        class="icon-btn"
        :disabled="agentStore.busy"
        :title="`让${agentStore.name}重新整理消息里的待办`"
        @click="rescan"
      >
        <RefreshCw :size="16" :class="{ spin: agentStore.busy }" />
      </button>
      <button class="icon-btn" title="新建 TODO" @click="startAdd"><Plus :size="18" /></button>
      </div>
    </header>

    <div ref="pageEl" class="page">

    <!-- 手机宽度：标题在顶部栏里，这里只留一行概况和「重新整理」 -->
    <div v-if="ws.isCompact" class="summary">
      <span>{{ later.length + today.length }} 项待办 · 今天 {{ today.length }} 项<template v-if="suggested.length"> · {{ suggested.length }} 项待确认</template></span>
      <button
        v-if="agentStore.aiOk"
        class="icon-btn ghost"
        :disabled="agentStore.busy"
        :title="`让${agentStore.name}重新整理消息里的待办`"
        @click="rescan"
      >
        <RefreshCw :size="16" :class="{ spin: agentStore.busy }" />
      </button>
    </div>

    <div v-if="adding" class="add">
      <input
        ref="newInput"
        v-model="newTitle"
        placeholder="写下要做的事，回车保存"
        @keydown.enter="saveNew"
        @keydown.esc="cancelAdd"
        @blur="!newTitle.trim() && cancelAdd()"
      />
    </div>

    <!-- 待确认：小A从消息里识别出的建议 -->
    <section v-if="suggested.length" class="group pending">
      <header>
        <h2><Sparkles :size="14" /> 待确认 <span>{{ suggested.length }}</span></h2>
        <button class="link" @click="ws.acceptTodos(suggested.map((t) => t.id))">全部加入</button>
      </header>
      <TransitionGroup name="list" tag="div" class="items">
        <TodoItem v-for="t in suggested" :key="t.id" :todo="t" :focused="focusId === t.id" />
      </TransitionGroup>
    </section>

    <section class="group">
      <header><h2>今天 <span>{{ today.length }}</span></h2></header>
      <TransitionGroup v-if="today.length" name="list" tag="div" class="items">
        <TodoItem v-for="t in today" :key="t.id" :todo="t" :focused="focusId === t.id" />
      </TransitionGroup>
      <div v-else class="empty">今天没有待办</div>
    </section>

    <section v-if="later.length" class="group">
      <header><h2>之后 <span>{{ later.length }}</span></h2></header>
      <TransitionGroup name="list" tag="div" class="items">
        <TodoItem v-for="t in later" :key="t.id" :todo="t" :focused="focusId === t.id" />
      </TransitionGroup>
    </section>

    <section v-if="done.length" class="group">
      <header>
        <button class="fold" @click="showDone = !showDone">
          <h2>已完成 <span>{{ done.length }}</span></h2>
          <ChevronDown :size="16" :class="{ up: showDone }" />
        </button>
      </header>
      <TransitionGroup v-if="showDone" name="list" tag="div" class="items">
        <TodoItem v-for="t in done" :key="t.id" :todo="t" :focused="focusId === t.id" />
      </TransitionGroup>
    </section>
    </div>
    <!-- 手机宽度：新建按钮悬浮在右下角，单手也够得着 -->
    <button v-if="ws.isCompact && !adding" class="fab-add" title="新建 TODO" aria-label="新建 TODO" @click="startAdd">
      <Plus :size="24" />
    </button>
  </div>
</template>

<style scoped>
.todo-page {
  /* 标题栏高度 */
  --head-h: 58px;
  position: relative;
  height: 100%;
}
.page {
  height: 100%;
  overflow-y: auto;
  padding: var(--head-h) 24px 40px;
  /* 标题栏区域内列表完全透明，下方 24px 渐进显现 */
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, transparent calc(var(--head-h) - 10px), #000 calc(var(--head-h) + 14px));
  mask-image: linear-gradient(to bottom, transparent 0, transparent calc(var(--head-h) - 10px), #000 calc(var(--head-h) + 14px));
}
.page > * {
  max-width: 820px;
  margin-left: auto;
  margin-right: auto;
}
.head {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 3;
  padding: 0 24px;
  pointer-events: none;
}
.head-inner {
  max-width: 820px;
  height: var(--head-h);
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  pointer-events: auto;
}
.head-inner h1 {
  flex: 1;
}
.icon-btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.add {
  margin-bottom: 14px;
}
.add input {
  width: 100%;
  height: 46px;
  padding: 0 16px;
  border: 0;
  outline: 0;
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: 0 0 0 1px var(--accent-ring), var(--shadow-sm);
  font-size: 14px;
}
.add input::placeholder {
  color: var(--text-3);
}
h1 {
  margin: 0;
  font-size: 22px;
}
.spin {
  animation: spin 0.9s linear infinite;
}


.group {
  margin-bottom: 18px;
}
.group > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
h2 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 15px;
  font-weight: 700;
}
h2 span {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-3);
}
.pending {
  padding: 12px;
  border-radius: var(--r-lg);
  background: linear-gradient(135deg, color-mix(in srgb, var(--accent-2) 6%, transparent), color-mix(in srgb, var(--accent) 10%, transparent));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 18%, transparent);
}
.pending h2 {
  color: var(--accent-strong);
}
.link {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent-strong);
}
.items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 22px;
  border-radius: 16px;
  background: var(--glass-1);
  font-size: 14px;
  color: var(--text-2);
}
.fold {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
}
.fold svg {
  transition: transform 0.2s;
}
.fold svg.up {
  transform: rotate(180deg);
}

.list-move,
.list-enter-active,
.list-leave-active {
  transition: all 0.35s var(--ease-spring);
}
.list-enter-from,
.list-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* —— 手机宽度 —— */
.compact {
  /* 没有浮动标题栏，列表从顶部开始 */
  --head-h: 0px;
}
.compact .page {
  /* 底部给悬浮的新建按钮留出位置，最后一项不会被挡住 */
  padding: 4px 12px 88px;
  -webkit-mask-image: none;
  mask-image: none;
}
.summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 40px;
  margin-bottom: 4px;
  padding-left: 4px;
  font-size: 13px;
  color: var(--text-2);
}
.compact .empty {
  padding: 12px;
}
.fab-add {
  position: absolute;
  right: 16px;
  bottom: 16px;
  z-index: 3;
  width: 52px;
  height: 52px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--accent);
  color: var(--on-accent);
  box-shadow: 0 6px 18px color-mix(in srgb, var(--accent) 35%, transparent), 0 2px 6px rgba(0, 0, 0, 0.12);
  transition: transform 0.2s var(--ease-spring);
  -webkit-tap-highlight-color: transparent;
}
.fab-add:active {
  transform: scale(0.92);
}

@container ws (max-width: 560px) {
  .page {
    padding: var(--head-h) 10px 24px;
  }
  .head {
    padding: 0 10px;
  }
  .pending {
    padding: 10px;
  }
}
</style>
