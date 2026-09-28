<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Settings, RefreshCw, ChevronLeft, Brain } from 'lucide-vue-next'
import AgentOrb from '@/components/common/AgentOrb.vue'
import AgentFeedItem from './AgentFeedItem.vue'
import AgentComposer from './AgentComposer.vue'
import { useAgent } from '@/stores/agent'
import { useWorkspace } from '@/stores/workspace'
import { stamp } from '@/api/format'

const route = useRoute()
const router = useRouter()
const store = useAgent()
const ws = useWorkspace()

// Agent 感知左侧主窗口正在看什么，作为对话上下文
const detached = ref(false)
const context = computed(() => {
  if (detached.value) return null
  switch (route.name) {
    case 'chat': {
      const convId = route.params.id || ws.conversations[0]?.id
      const c = ws.getConv(convId)
      return c && { view: 'chat', convId, label: `消息 · ${ws.convTitle(c)}` }
    }
    case 'todo':
      return { view: 'todo', label: 'TODO' }
    case 'memory':
      return { view: 'memory', label: '记忆' }
    case 'search':
      return { view: 'search', label: '搜索' }
  }
  return null
})
watch(() => route.fullPath, () => (detached.value = false))


// 对话流顶部的时间：第一条卡片的时间
const feedStart = computed(() => {
  const first = store.feed.find((f) => f.createdAt)
  return first && stamp(first.createdAt)
})

// 手机标题栏里的状态文字，跟着小A的情绪变
const STATUS = { thinking: '思考中…', listening: '正在听…', happy: '好的', offline: '暂时不可用' }
const statusText = computed(() => STATUS[store.mood] ?? (store.profile.tagline || '在线'))
// 手机上没有右键菜单，记忆页从这里进：收起面板再跳转
function openMemory() {
  ws.agentCollapsed = true
  router.push('/memory')
}

const stream = ref(null)
function scrollToBottom(smooth = true) {
  nextTick(() => stream.value?.scrollTo({ top: stream.value.scrollHeight, behavior: smooth ? 'smooth' : 'auto' }))
}
watch(() => store.feed.length, () => scrollToBottom())
scrollToBottom(false)

// 底部输入区浮在对话流之上：测量其高度，给对话流底部留出等高空间
const footEl = ref(null)
const footH = ref(120)
let ro
onMounted(() => {
  ro = new ResizeObserver(([e]) => {
    const el = stream.value
    const atBottom = el && el.scrollHeight - el.scrollTop - el.clientHeight < 40
    footH.value = Math.ceil(e.borderBoxSize?.[0]?.blockSize ?? e.target.offsetHeight)
    if (atBottom) scrollToBottom(false)
  })
  ro.observe(footEl.value)
})
onBeforeUnmount(() => ro?.disconnect())

function send(text) {
  store.send(text, context.value)
}
</script>

<template>
  <section class="agent-pane" :class="{ compact: ws.isCompact }" :style="{ '--foot-h': footH + 'px' }">
    <header class="head">
      <!-- 桌面宽度：小A头像悬浮在窗口右上角（见 AppShell），这里留空。
           手机宽度：面板占满全屏，给一个完整的标题栏——返回、身份和状态、记忆、设置 -->
      <template v-if="ws.isCompact">
        <button class="icon-btn ghost back" title="返回" aria-label="返回" @click="ws.agentCollapsed = true">
          <ChevronLeft :size="24" />
        </button>
        <div class="title">
          <AgentOrb :size="30" />
          <span class="title-text">
            <b>{{ store.name }}</b>
            <span class="status" :class="store.mood">{{ statusText }}</span>
          </span>
        </div>
        <button class="icon-btn ghost" title="记忆" aria-label="记忆" @click="openMemory"><Brain :size="19" /></button>
        <button class="icon-btn ghost" title="设置" aria-label="设置" @click="ws.settingsOpen = true">
          <Settings :size="19" />
        </button>
      </template>
    </header>

    <!-- AI 不可用：只留一行状态和重试 -->
    <div v-if="!store.aiOk" class="offline">
      <span>{{ store.name }}暂时不可用</span>
      <button class="btn btn-plain" :disabled="store.checking" @click="store.retry()">
        <RefreshCw :size="14" :class="{ spin: store.checking }" /> 重试
      </button>
    </div>

    <div v-show="store.aiOk" ref="stream" class="stream">
      <div v-if="feedStart" class="day-sep">{{ feedStart }}</div>
      <AgentFeedItem v-for="item in store.feed" :key="item.id" :item="item" />
    </div>

    <footer v-show="store.aiOk" ref="footEl" class="foot">
      <div class="quick">
        <button v-for="p in store.profile.quickPrompts" :key="p" class="chip" :disabled="store.busy" @click="send(p)">{{ p }}</button>
      </div>
      <AgentComposer :context="context" :busy="store.busy" @send="send" @detach="detached = true" />
    </footer>

  </section>
</template>

<style scoped>
.agent-pane {
  /* 顶部头像区高度（与主窗口顶部栏一致） */
  --head-h: 64px;
  position: relative;
  width: var(--agent-w, 440px);
  height: 100%;
  display: flex;
  flex-direction: column;
}
.head {
  /* 头像区浮在对话流之上，背景从窗口底色渐进到全透明，对话可从下方滚过 */
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 2;
  height: calc(var(--head-h) + 20px);
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 6px 0 12px;
  background: linear-gradient(
    to bottom,
    var(--window) 0%,
    var(--window) 38%,
    color-mix(in srgb, var(--window) 70%, transparent) 62%,
    transparent 100%
  );
  pointer-events: none;
}
.head > * {
  pointer-events: auto;
}
.compact {
  --head-h: 56px;
}
.compact .head {
  align-items: center;
  gap: 2px;
  height: calc(var(--head-h) + 16px);
  padding: 0 6px 16px;
}
.back {
  margin-right: 2px;
}
.title {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}
.title-text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}
.title-text b {
  font-size: 16px;
}
.status {
  overflow: hidden;
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
  text-overflow: ellipsis;
  transition: color 0.2s;
}
.status.thinking,
.status.listening {
  color: var(--accent-strong);
}
.compact .foot {
  /* 给 iPhone 底部的横条留出位置 */
  padding: 0 8px calc(8px + env(safe-area-inset-bottom));
}
.compact .chip {
  height: 34px;
  padding: 0 14px;
}

.offline {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 14px;
  color: var(--text-2);
}
.offline .btn:disabled {
  opacity: 0.6;
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.stream {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* 顶部留出头像区、底部留出浮动输入区的高度，对话可从两者下方滚过 */
  padding: calc(var(--head-h) + 4px) 12px calc(var(--foot-h) + 12px);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.day-sep {
  align-self: center;
  font-size: 12px;
  color: var(--text-3);
  padding: 4px 0;
}

.foot {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 8px 8px;
  background: none;
  /* 容器透明且不拦截点击，只有其中的控件可交互 */
  pointer-events: none;
}
.quick {
  display: flex;
  gap: 6px;
  padding: 0 4px;
  overflow-x: auto;
  scrollbar-width: none;
  /* 自己能接收触摸，手指在按钮之间的空隙也能横向滑动 */
  pointer-events: auto;
  overscroll-behavior-x: contain;
  /* 右边缘渐隐，提示还能往右滑 */
  -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 28px), transparent);
  mask-image: linear-gradient(to right, #000 calc(100% - 28px), transparent);
}
.quick::-webkit-scrollbar {
  display: none;
}
.chip {
  flex: none;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 13px;
  color: var(--text-2);
  background: var(--card);
  box-shadow: 0 0 0 0.5px var(--line-strong), var(--shadow-sm);
  pointer-events: auto;
  transition: all 0.15s;
}
.chip:hover:not(:disabled) {
  color: var(--accent-strong);
  background: color-mix(in srgb, var(--accent) 12%, var(--card));
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--accent) 40%, transparent);
}
.chip:disabled {
  opacity: 0.5;
  cursor: default;
}
</style>
