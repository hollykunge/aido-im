<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Search, Minus, Square, Copy, X, Settings, Brain, MessageCircle, ListTodo,
  Ellipsis, CalendarDays, FileText, Stamp, LogOut, UserRound,
} from 'lucide-vue-next'
import AgentPane from '@/components/agent/AgentPane.vue'
import AgentLookSettings from '@/components/agent/AgentLookSettings.vue'
import ProfileDialog from '@/components/user/ProfileDialog.vue'
import AgentOrb from '@/components/common/AgentOrb.vue'
import Avatar from '@/components/common/Avatar.vue'
import { useTheme } from '@/composables/useTheme'
import { useWorkspace } from '@/stores/workspace'
import { useAgent } from '@/stores/agent'
import { useRealtime } from '@/stores/realtime'
import { api } from '@/api'
import { tabs } from '@/router'

const route = useRoute()
const router = useRouter()
const ws = useWorkspace()
const agentStore = useAgent()
// 应用启动时就开始应用主题并跟随系统变化（切换入口在设置面板里）
useTheme()

// 小A展开时，主功能区叠加与小A头像同一套配色的淡渐变
const tintVars = computed(() => {
  const p = agentStore.palette
  return { '--t0': p.base, '--t1': p.colors[0], '--t2': p.colors[1], '--t3': p.colors[2], '--t4': p.colors[3] }
})

// 头像右下角的状态点反映实时推送是否连着
const realtime = useRealtime()
const PRESENCE = { open: '在线', connecting: '连接中…', offline: '离线，正在重连' }
const presence = computed(() => ({ label: PRESENCE[realtime.status] }))

const badges = computed(() => ({ chat: ws.unreadTotal, todo: ws.openTodoCount }))
const activeTab = computed(() => route.name)
const tabIcons = { chat: MessageCircle, todo: ListTodo, search: Search }

// —— 手机宽度：tab 栏移到底部作为导航条，顶部栏中间显示当前页面标题 ——
const PAGE_TITLES = { memory: '记忆' }
const pageTitle = computed(() => tabs.find((t) => t.name === route.name)?.label ?? PAGE_TITLES[route.name] ?? '')
// 进入某个会话后全屏：隐藏顶部栏、悬浮的小A头像和底部导航，会话自己的标题栏带返回（返回会话列表后恢复）
const inConversation = computed(() => ws.isCompact && route.name === 'chat' && !!route.params.id)
const showBottomNav = computed(() => ws.isCompact && !inConversation.value)

// —— 顶部 tab 栏：始终居中、高度不变；两侧放不下完整文字时收成纯图标 ——
// 用一份隐藏的「完整文字版」tab 栏量出所需宽度，与当前模式无关，不会来回抖动
const TAB_FIT_SLACK = 24 // 提前一点切换，给文字收起动画留余量
const headerEl = ref(null)
const measureEl = ref(null)
const iconOnly = ref(false)
function fitTabs() {
  const h = headerEl.value
  const m = measureEl.value
  if (!h || !m) return
  const cs = getComputedStyle(h)
  // 左右两列各至少容纳一个头像（用户头像 / 小A头像）加列间距，tab 栏才能正好居中
  const side = parseFloat(cs.getPropertyValue('--bar-h')) + parseFloat(cs.columnGap)
  const avail = h.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight) - side * 2
  iconOnly.value = m.offsetWidth + TAB_FIT_SLACK > avail
}

// —— Agent 容器宽度：拖拽分隔条调整 ——
const WS_MIN = 360 // 主窗口至少保留的宽度
const COLLAPSE_AT = 240 // 拖到比这更窄时松手即收起
const GUTTER = 10 // 主区域与小A之间的拖拽条宽度
const EDGE = 4 // 主区域、小A与窗口边框的距离（与 .win-body 的 padding 一致）

const winEl = ref(null)
// 初始按视口估算（减去外边距），避免首帧宽度被压到最小再弹开
const winWidth = ref(window.innerWidth - 36 - EDGE * 2)
let ro
onMounted(() => {
  ro = new ResizeObserver((entries) => {
    for (const e of entries) if (e.target === winEl.value) winWidth.value = e.contentRect.width
    fitTabs()
  })
  ro.observe(winEl.value)
  // 主窗口随小A展开 / 收起逐帧变宽变窄；未读数变化会改变完整 tab 栏的宽度
  ro.observe(headerEl.value)
  ro.observe(measureEl.value)
  window.addEventListener('keydown', onHotkey)
})
onBeforeUnmount(() => {
  ro?.disconnect()
  window.removeEventListener('keydown', onHotkey)
})

// 小A头像右键菜单（记忆 / 设置）、tab 栏「更多」菜单
const fabMenu = ref(false)
const fabMenuEl = ref(null)
const moreMenu = ref(false)
const moreEl = ref(null)
// 用户头像菜单：身份、在线状态、退出登录
const userMenu = ref(false)
const userEl = ref(null)
function closeMenus() {
  fabMenu.value = false
  moreMenu.value = false
  userMenu.value = false
}
const profileOpen = ref(false)
function openProfile() {
  closeMenus()
  profileOpen.value = true
}
// 退出后整页重新加载：清掉内存里这位用户的数据，回到登录页
const loggingOut = ref(false)
async function logout() {
  loggingOut.value = true
  await api.logout().catch(() => {})
  location.reload()
}
function openMemory() {
  closeMenus()
  router.push('/memory')
}
function openSettings() {
  closeMenus()
  ws.settingsOpen = true
}
// 「更多」菜单里尚未开放的入口，先占位展示
const moreSoon = [
  { label: '日历', icon: CalendarDays },
  { label: '云文档', icon: FileText },
  { label: '审批', icon: Stamp },
]
function onDocDown(e) {
  if (fabMenu.value && !fabMenuEl.value?.contains(e.target)) fabMenu.value = false
  if (moreMenu.value && !moreEl.value?.contains(e.target)) moreMenu.value = false
  if (userMenu.value && !userEl.value?.contains(e.target)) userMenu.value = false
}
const onEsc = (e) => e.key === 'Escape' && closeMenus()
document.addEventListener('pointerdown', onDocDown)
window.addEventListener('keydown', onEsc)
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocDown)
  window.removeEventListener('keydown', onEsc)
})

// ⌘K / Ctrl+K：从任意页面打开全局搜索
function onHotkey(e) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (route.name === 'search') window.dispatchEvent(new Event('aido:focus-search'))
    else router.push('/search')
  }
}

const maxWidth = computed(() => Math.max(ws.AGENT_W.min, Math.min(ws.AGENT_W.max, winWidth.value - GUTTER - WS_MIN)))
const agentWidth = computed(() => Math.min(ws.agentWidth, maxWidth.value))
const slotStyle = computed(() => {
  if (ws.isCompact) return {}
  return { width: ws.agentCollapsed ? '0px' : `${agentWidth.value}px`, '--agent-w': `${agentWidth.value}px` }
})

// Windows 窗口：最大化 / 还原
const maximized = ref(false)

const resizing = ref(false)
const willCollapse = ref(false)
function startResize(e) {
  if (ws.agentCollapsed || e.button !== 0) return
  const handle = e.currentTarget
  const startWidth = ws.agentWidth
  const right = winEl.value.getBoundingClientRect().right - EDGE
  handle.setPointerCapture(e.pointerId)
  resizing.value = true

  const move = (ev) => {
    const w = right - ev.clientX - GUTTER / 2
    willCollapse.value = w < COLLAPSE_AT
    ws.agentWidth = Math.round(Math.max(ws.AGENT_W.min, Math.min(maxWidth.value, w)))
  }
  const up = () => {
    handle.removeEventListener('pointermove', move)
    handle.removeEventListener('pointerup', up)
    handle.removeEventListener('pointercancel', up)
    resizing.value = false
    // 拖到底收起时，保留拖拽前的宽度，下次展开恢复
    if (willCollapse.value) {
      ws.agentWidth = startWidth
      ws.agentCollapsed = true
    }
    willCollapse.value = false
  }
  handle.addEventListener('pointermove', move)
  handle.addEventListener('pointerup', up)
  handle.addEventListener('pointercancel', up)
}
function onResizerKey(e) {
  const step = e.shiftKey ? 64 : 16
  // Agent 在右侧：向左键加宽，向右键变窄
  if (e.key === 'ArrowLeft') ws.agentWidth = Math.min(maxWidth.value, agentWidth.value + step)
  else if (e.key === 'ArrowRight') ws.agentWidth = Math.max(ws.AGENT_W.min, agentWidth.value - step)
  else return
  e.preventDefault()
}
</script>

<template>
  <div class="desktop" :class="{ compact: ws.isCompact, maximized }">
    <div
      class="window"
      :class="{ collapsed: ws.agentCollapsed, narrow: ws.isNarrow, compact: ws.isCompact, 'in-conv': inConversation, resizing }"
    >
      <!-- Windows 标题栏 -->
      <header class="titlebar" @dblclick.self="maximized = !maximized">
        <div class="app" @dblclick="maximized = !maximized">
          <img class="logo" src="/favicon.svg" alt="" />
          <span>AIDo</span>
        </div>
        <div class="caption">
          <button class="tool" :class="{ on: ws.settingsOpen }" title="设置" @click="ws.settingsOpen = !ws.settingsOpen">
            <Settings :size="15" :stroke-width="1.5" />
          </button>
          <button title="最小化"><Minus :size="15" :stroke-width="1.25" /></button>
          <button :title="maximized ? '还原' : '最大化'" @click="maximized = !maximized">
            <Copy v-if="maximized" :size="13" :stroke-width="1.25" class="restore" />
            <Square v-else :size="12" :stroke-width="1.25" />
          </button>
          <button class="close" title="关闭"><X :size="16" :stroke-width="1.25" /></button>
        </div>
      </header>

      <div ref="winEl" class="win-body">
        <!-- 小A头像：唯一入口，悬浮在右上角，点击展开 / 收起 -->
        <button
          class="agent-fab"
          :class="{ open: !ws.agentCollapsed }"
          :title="`${ws.agentCollapsed ? '展开' : '收起'}${agentStore.name} · 右键更多`"
          @click="ws.agentCollapsed = !ws.agentCollapsed"
          @contextmenu.prevent="fabMenu = true"
        >
          <AgentOrb :size="32" />
        </button>
        <Transition name="menu">
          <div v-if="fabMenu" ref="fabMenuEl" class="pop-menu fab-menu" role="menu">
            <button role="menuitem" @click="openMemory"><Brain :size="15" /> 记忆</button>
            <button role="menuitem" @click="openSettings"><Settings :size="15" /> 设置</button>
          </div>
        </Transition>

      <!-- 主窗口：消息 / TODO / 搜索 / 更多（记忆在小A头像右键菜单里） -->
      <main class="workspace" :style="tintVars">
        <header ref="headerEl" class="ws-header">
          <div class="head-left">
            <!-- 用户头像：圆形，在线状态在右下角 -->
            <div ref="userEl" class="user">
              <button
                class="me"
                :title="`${ws.me?.name} · ${presence.label}`"
                aria-haspopup="menu"
                :aria-expanded="userMenu"
                @click="userMenu = !userMenu"
              >
                <Avatar :user="ws.meId" :size="40" />
                <!-- 实时推送连接状态：绿 在线 / 黄 连接中 / 灰 离线 -->
                <span class="status-dot" :class="realtime.status" />
              </button>
              <Transition name="menu">
                <div v-if="userMenu" class="pop-menu user-menu" role="menu">
                  <div class="who">
                    <b>{{ ws.me?.name }}</b>
                    <span>{{ ws.me?.dept }} · {{ ws.me?.role }}</span>
                    <span class="presence" :class="realtime.status">{{ presence.label }}</span>
                  </div>
                  <button role="menuitem" @click="openProfile"><UserRound :size="15" /> 个人信息</button>
                  <hr />
                  <button role="menuitem" class="danger" :disabled="loggingOut" @click="logout">
                    <LogOut :size="15" /> {{ loggingOut ? '正在退出…' : '退出登录' }}
                  </button>
                </div>
              </Transition>
            </div>
          </div>

          <h1 v-if="ws.isCompact" class="page-title">{{ pageTitle }}</h1>
          <nav v-else class="tabs" :class="{ 'icon-only': iconOnly }">
            <button
              v-for="t in tabs"
              :key="t.name"
              class="tab"
              :class="{ active: activeTab === t.name }"
              :title="iconOnly ? t.label : undefined"
              @click="router.push(t.path)"
            >
              <span class="tab-icon"><component :is="tabIcons[t.name]" :size="16" /></span>
              <span class="tab-label"><span>{{ t.label }}</span></span>
              <span v-if="badges[t.name]" class="badge">{{ badges[t.name] > 99 ? '99+' : badges[t.name] }}</span>
            </button>

            <!-- 更多：点击弹出菜单 -->
            <div ref="moreEl" class="more">
              <button
                class="tab"
                :class="{ active: moreMenu }"
                :title="iconOnly ? '更多' : undefined"
                aria-haspopup="menu"
                :aria-expanded="moreMenu"
                @click="moreMenu = !moreMenu"
              >
                <span class="tab-icon"><Ellipsis :size="16" /></span>
                <span class="tab-label"><span>更多</span></span>
              </button>
              <Transition name="menu">
                <div v-if="moreMenu" class="pop-menu more-menu" role="menu">
                  <button v-for="m in moreSoon" :key="m.label" role="menuitem" disabled>
                    <component :is="m.icon" :size="15" /> {{ m.label }}
                    <span class="soon">即将上线</span>
                  </button>
                </div>
              </Transition>
            </div>
          </nav>

          <!-- 隐藏的完整文字版 tab 栏，只用来量宽度 -->
          <div class="measure-box" aria-hidden="true">
          <div ref="measureEl" class="tabs measure">
            <span v-for="t in tabs" :key="t.name" class="tab">
              <span class="tab-icon"><component :is="tabIcons[t.name]" :size="16" /></span>
              <span class="tab-label"><span>{{ t.label }}</span></span>
              <span v-if="badges[t.name]" class="badge">{{ badges[t.name] > 99 ? '99+' : badges[t.name] }}</span>
            </span>
            <span class="tab">
              <span class="tab-icon"><Ellipsis :size="16" /></span>
              <span class="tab-label"><span>更多</span></span>
            </span>
          </div>
          </div>
        </header>

        <div class="ws-body">
          <RouterView v-slot="{ Component }">
            <Transition name="fade" mode="out-in">
              <component :is="Component" :key="route.name" />
            </Transition>
          </RouterView>
        </div>

        <!-- 手机宽度的底部导航 -->
        <nav v-if="showBottomNav" class="bottom-nav" aria-label="主导航">
          <button
            v-for="t in tabs"
            :key="t.name"
            class="nav-item"
            :class="{ active: activeTab === t.name }"
            :aria-current="activeTab === t.name ? 'page' : undefined"
            @click="router.push(t.path)"
          >
            <span class="nav-icon">
              <component :is="tabIcons[t.name]" :size="22" />
              <span v-if="badges[t.name]" class="nav-badge">{{ badges[t.name] > 99 ? '99+' : badges[t.name] }}</span>
            </span>
            <span class="nav-label">{{ t.label }}</span>
          </button>
          <div ref="moreEl" class="nav-more">
            <button
              class="nav-item"
              :class="{ active: moreMenu }"
              aria-haspopup="menu"
              :aria-expanded="moreMenu"
              @click="moreMenu = !moreMenu"
            >
              <span class="nav-icon"><Ellipsis :size="22" /></span>
              <span class="nav-label">更多</span>
            </button>
            <Transition name="menu-up">
              <div v-if="moreMenu" class="pop-menu more-menu up" role="menu">
                <button v-for="m in moreSoon" :key="m.label" role="menuitem" disabled>
                  <component :is="m.icon" :size="15" /> {{ m.label }}
                  <span class="soon">即将上线</span>
                </button>
              </div>
            </Transition>
          </div>
        </nav>

      </main>

      <!-- 拖拽分隔条 -->
      <div
        class="resizer"
        role="separator"
        aria-orientation="vertical"
        :aria-valuenow="agentWidth"
        :aria-valuemin="ws.AGENT_W.min"
        :aria-valuemax="maxWidth"
        :tabindex="ws.agentCollapsed ? -1 : 0"
        title="拖拽调整宽度 · 双击恢复默认"
        @pointerdown="startResize"
        @dblclick="ws.agentWidth = ws.AGENT_W.default"
        @keydown="onResizerKey"
      >
        <i />
      </div>

      <!-- 外层容器：个人 Agent（右侧） -->
      <aside class="agent-slot" :class="{ 'will-collapse': willCollapse }" :style="slotStyle" :aria-hidden="ws.agentCollapsed">
        <AgentPane />
      </aside>
      </div>

      <!-- 设置面板：从标题栏右上角弹出 -->
      <AgentLookSettings v-if="ws.settingsOpen" @close="ws.settingsOpen = false" />
      <ProfileDialog v-if="profileOpen" @close="profileOpen = false" />

      <!-- 接口出错时的轻提示 -->
      <Transition name="toast">
        <div v-if="ws.toast" class="toast" role="status">{{ ws.toast }}</div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.desktop {
  height: 100%;
  padding: 18px;
}
.desktop.maximized {
  padding: 0;
}
.window {
  position: relative;
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 8px;
  background: var(--window);
  box-shadow: var(--shadow-window);
  overflow: hidden;
  transition: border-radius 0.2s;
}

.maximized .window {
  border-radius: 0;
}
.win-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  padding: 0 4px 4px;
}

/* —— Windows 11 标题栏 —— */
.titlebar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 32px;
  user-select: none;
}
.app {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 12px;
  font-size: 12px;
  color: var(--text-2);
}
.logo {
  /* 与浏览器标签页使用同一个应用图标 */
  width: 16px;
  height: 16px;
  display: block;
}
.caption {
  display: flex;
  height: 100%;
}
.caption button {
  width: 46px;
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--text-1);
  transition: background 0.1s;
}
.caption button:hover {
  background: var(--hover-strong);
}
.caption button:active {
  background: var(--hover);
}
.caption .tool {
  margin-right: 4px;
  color: var(--text-2);
}
.caption .tool.on {
  background: var(--hover-strong);
  color: var(--text-1);
}
.caption .close:hover {
  background: #c42b1c;
  color: #fff;
}
.caption .close:active {
  background: #c53f32;
}
.restore {
  transform: scaleX(-1);
}

.agent-slot {
  flex: none;
  overflow: hidden;
  transition: width 0.5s var(--ease-spring), opacity 0.3s;
}
.collapsed .agent-slot {
  opacity: 0;
}
.agent-slot.will-collapse {
  opacity: 0.45;
}

/* 拖拽分隔条 */
.resizer {
  flex: none;
  position: relative;
  width: 10px;
  display: grid;
  place-items: center;
  cursor: col-resize;
  touch-action: none;
  outline: none;
  transition: width 0.5s var(--ease-spring);
}
.resizer i {
  width: 4px;
  height: 36px;
  border-radius: 2px;
  background: transparent;
  transition: background 0.15s, height 0.2s var(--ease-spring);
}
.resizer:hover i,
.resizer:focus-visible i,
.resizing .resizer i {
  height: 64px;
  background: var(--accent);
}
.collapsed .resizer {
  width: 0;
  pointer-events: none;
}
.resizing,
.resizing * {
  cursor: col-resize !important;
  user-select: none;
}
.resizing .agent-slot,
.resizing .resizer {
  transition: none;
}

.workspace {
  position: relative;
  isolation: isolate;
  flex: 1;
  min-width: 0;
  container: shell / inline-size;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  background: var(--workspace);
  overflow: clip;
}
/* 小A配色渐变层：头像颜色少量混入底色（浅色混白、深色混黑），展开小A时淡入 */
.workspace::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background:
    radial-gradient(70% 60% at 100% 0%, color-mix(in srgb, var(--t2) 22%, transparent), transparent 70%),
    radial-gradient(70% 60% at 0% 100%, color-mix(in srgb, var(--t3) 20%, transparent), transparent 70%),
    linear-gradient(
      160deg,
      color-mix(in srgb, var(--t1) 12%, var(--workspace)) 0%,
      color-mix(in srgb, var(--t0) 8%, var(--workspace)) 50%,
      color-mix(in srgb, var(--t4) 14%, var(--workspace)) 100%
    );
  opacity: 0;
  transition: opacity 0.5s;
}
.window:not(.collapsed) .workspace::before {
  opacity: 1;
}
.ws-header {
  /* 顶部栏控件统一高度：与 tab 栏一致；任何宽度下高度都不变 */
  --bar-h: 40px;
  --pad: 16px;
  position: relative;
  z-index: 2;
  flex: none;
  height: 64px;
  display: grid;
  /* 两侧等宽且至少放得下一个头像，tab 栏始终在主窗口正中 */
  grid-template-columns: minmax(var(--bar-h), 1fr) auto minmax(var(--bar-h), 1fr);
  align-items: center;
  column-gap: 12px;
  padding: 0 var(--pad);
}
.tabs {
  --tab-ease: cubic-bezier(0.25, 0.8, 0.25, 1);
  justify-self: center;
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 999px;
  background: var(--seg-track);
  box-shadow: inset 0 0 0 1px var(--seg-border), var(--seg-shadow);
}
.tab {
  position: relative;
  flex: none;
  white-space: nowrap;
  display: flex;
  align-items: center;
  height: calc(var(--bar-h) - 6px);
  padding: 0 14px;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-2);
  transition: background 0.2s, color 0.2s, padding 0.3s var(--tab-ease);
}
.tab:hover {
  color: var(--text-1);
}
.tab.active {
  background: var(--seg-active);
  color: var(--text-1);
  font-weight: 600;
  box-shadow: var(--seg-active-shadow);
}
/* 每个 tab 都是「图标 + 文字」；切纯图标时文字用 0fr ⇄ 1fr 的网格列做宽度动画，平滑收起 */
.tab-icon {
  display: flex;
  margin-right: 5px;
  transition: margin 0.3s var(--tab-ease);
}
.tab-label {
  display: grid;
  grid-template-columns: 1fr;
  overflow: hidden;
  transition: grid-template-columns 0.3s var(--tab-ease), opacity 0.2s;
}
.tab-label > * {
  min-width: 0;
}
/* 纯图标模式：文字收起，改为悬停提示 */
.icon-only .tab {
  padding: 0 11px;
}
.icon-only .tab-icon {
  margin-right: 0;
}
.icon-only .tab-label {
  grid-template-columns: 0fr;
  opacity: 0;
}
.more {
  position: relative;
  display: flex;
}
.pop-menu.user-menu {
  top: calc(100% + 9px);
  left: -2px;
  min-width: 200px;
  transform-origin: top left;
}
.user-menu .who {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px 10px;
  margin-bottom: 4px;
  border-bottom: 0.5px solid var(--line);
  font-size: 12px;
  color: var(--text-3);
}
.user-menu .who b {
  font-size: 14px;
  color: var(--text-1);
}
.user-menu .presence::before {
  content: '';
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 5px;
  border-radius: 50%;
  background: #22c55e;
  vertical-align: 1px;
}
.user-menu .presence.connecting::before {
  background: #f59e0b;
}
.user-menu .presence.offline::before {
  background: var(--text-3);
}
.pop-menu hr {
  height: 0.5px;
  margin: 4px 6px;
  border: 0;
  background: var(--line);
}
.pop-menu button.danger:hover:not(:disabled),
.pop-menu button.danger:hover:not(:disabled) svg {
  color: var(--danger);
}
.pop-menu.more-menu {
  top: calc(100% + 9px);
  right: -3px;
  min-width: 180px;
}
/* 量宽用的隐藏 tab 栏放进零尺寸盒子里，比主窗口宽时也不会撑出滚动 */
.measure-box {
  position: absolute;
  top: 0;
  left: 0;
  width: 0;
  height: 0;
  overflow: hidden;
  visibility: hidden;
  pointer-events: none;
}
.tabs.measure {
  width: max-content;
}
.badge {
  flex: none;
  margin-left: 5px;
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
.tab.active .badge {
  background: var(--accent);
}
/* 纯图标模式：未读数缩成图标右上角的小角标，不占 tab 宽度 */
.icon-only .badge {
  position: absolute;
  top: 3px;
  left: 20px;
  min-width: 15px;
  height: 15px;
  margin-left: 0;
  padding: 0 4px;
  border-radius: 8px;
  font-size: 9.5px;
  line-height: 15px;
  box-shadow: 0 0 0 1.5px var(--card);
  pointer-events: none;
  animation: badge-pop 0.3s var(--ease-spring);
}
.icon-only .tab.active .badge {
  box-shadow: 0 0 0 1.5px var(--seg-active);
}
@keyframes badge-pop {
  from {
    opacity: 0;
    transform: scale(0.5);
  }
}
.head-left {
  justify-self: start;
  display: flex;
  align-items: center;
  gap: 8px;
}
.user {
  position: relative;
}
.me {
  position: relative;
  flex: none;
  display: block;
  border-radius: 50%;
  width: var(--bar-h);
  height: var(--bar-h);
  cursor: pointer;
}
.me .avatar {
  width: 100% !important;
  height: 100% !important;
  box-shadow: var(--seg-shadow), inset 0 0 0 0.5px rgba(0, 0, 0, 0.06);
}
.status-dot {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 2px var(--card);
  transition: background 0.3s;
}
.status-dot.connecting {
  background: #f59e0b;
}
.status-dot.offline {
  background: var(--text-3);
}

.ws-body {
  flex: 1;
  min-height: 0;
  position: relative;
  /* 自成层叠上下文：页面内部的 z-index 不会盖过顶部栏弹出的菜单 */
  z-index: 0;
  /* 各页面按主窗口宽度（而非视口宽度）做响应式 */
  container: ws / inline-size;
}

/* —— 弹出菜单：小A头像右键菜单 / 「更多」菜单 —— */
.pop-menu {
  position: absolute;
  z-index: 6;
  min-width: 140px;
  padding: 5px;
  border-radius: 14px;
  background: var(--popover);
  box-shadow: var(--shadow-pop), 0 0 0 0.5px var(--line-strong);
  transform-origin: top right;
}
.pop-menu button {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 10px;
  border-radius: 9px;
  font-size: 13px;
  color: var(--text-1);
  text-align: left;
  white-space: nowrap;
}
.pop-menu button:hover:not(:disabled) {
  background: var(--list-hover);
}
.pop-menu button svg {
  flex: none;
  color: var(--text-2);
}
.pop-menu button:disabled {
  color: var(--text-3);
  cursor: default;
}
.pop-menu button:disabled svg {
  color: var(--text-3);
}
.pop-menu .soon {
  margin-left: auto;
  padding-left: 12px;
  font-size: 11px;
  color: var(--text-3);
}
.fab-menu {
  top: 58px;
  right: 16px;
}
.collapsed .fab-menu {
  right: 20px;
}
.compact .fab-menu {
  top: 54px;
  right: 10px;
}
.compact.collapsed .fab-menu {
  top: 58px;
  right: 12px;
}
.menu-enter-active {
  transition: opacity 0.18s, transform 0.25s var(--ease-spring);
}
.menu-leave-active {
  transition: opacity 0.12s;
}
.menu-enter-from,
.menu-leave-to {
  opacity: 0;
  transform: scale(0.94) translateY(-4px);
}

/* —— 轻提示 —— */
.toast {
  position: absolute;
  left: 50%;
  bottom: 24px;
  /* 在设置、个人信息等弹窗之上，弹窗里的操作出错也看得见 */
  z-index: 40;
  max-width: calc(100% - 32px);
  padding: 9px 16px;
  border-radius: 999px;
  background: var(--popover);
  box-shadow: var(--shadow-pop), 0 0 0 0.5px var(--line-strong);
  font-size: 13px;
  color: var(--text-1);
  transform: translateX(-50%);
}
.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s, transform 0.25s var(--ease-spring);
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}

/* —— 悬浮的小A头像 —— */
.agent-fab {
  position: absolute;
  top: 12px;
  right: 16px;
  z-index: 5;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--card);
  box-shadow: var(--shadow-md), 0 0 0 0.5px var(--line-strong);
  transition: transform 0.2s var(--ease-spring), box-shadow 0.2s, right 0.5s var(--ease-spring);
}
.agent-fab:hover {
  transform: scale(1.06);
}
.agent-fab:active {
  transform: scale(0.95);
}
.agent-fab.open {
  box-shadow: var(--shadow-md), 0 0 0 2px var(--accent-soft), 0 0 0 0.5px var(--line-strong);
}
/* 小A收起时，头像落在主窗口顶部栏右列，与左侧用户头像对称（win-body 内边距 4 + 顶部栏内边距 16） */
.collapsed .agent-fab {
  right: 20px;
}
.compact .agent-fab {
  top: 8px;
  right: 10px;
}
/* 手机宽度下小A面板有自己的标题栏（带返回），不再需要悬浮头像 */
.window.compact:not(.collapsed) .agent-fab {
  display: none;
}
/* 手机宽度下进入会话：全屏显示会话，小A入口在会话标题栏里 */
.window.in-conv .ws-header,
.window.in-conv .agent-fab {
  display: none;
}
.compact.collapsed .agent-fab {
  top: 12px;
  right: 12px;
}

/* —— 手机宽度：铺满屏幕 —— */
.compact.window {
  border-radius: 0;
}
.compact .titlebar {
  display: none;
}
.compact .win-body {
  padding: 0;
}
.compact .workspace {
  border-radius: 0;
}
/* 手机宽度下 Agent 与主窗口二选一，都占满整屏 */
.compact .agent-slot {
  width: 100%;
  --agent-w: 100%;
}
.compact.collapsed .agent-slot {
  width: 0;
}
.window.compact:not(.collapsed) .workspace {
  display: none;
}
.compact .resizer {
  display: none;
}
.desktop.compact {
  padding: 0;
}

.compact .ws-header {
  --pad: 12px;
}
.page-title {
  justify-self: center;
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}

/* —— 手机宽度的底部导航 —— */
.bottom-nav {
  flex: none;
  display: flex;
  /* 给 iPhone 底部的横条留出位置 */
  padding: 0 4px env(safe-area-inset-bottom);
  border-top: 0.5px solid var(--line-strong);
  background: color-mix(in srgb, var(--workspace) 92%, transparent);
  backdrop-filter: blur(16px);
}
.nav-item,
.nav-more {
  flex: 1;
  min-width: 0;
}
.nav-more {
  position: relative;
  display: flex;
}
.nav-item {
  height: 56px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  color: var(--text-3);
  -webkit-tap-highlight-color: transparent;
  transition: color 0.15s;
}
.nav-item:active {
  transform: scale(0.94);
}
.nav-item.active {
  color: var(--accent-strong);
}
.nav-icon {
  position: relative;
  display: grid;
  place-items: center;
}
.nav-label {
  font-size: 11px;
  font-weight: 500;
}
.nav-badge {
  position: absolute;
  top: -5px;
  left: 14px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--danger);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
  box-shadow: 0 0 0 1.5px var(--workspace);
}
/* 底部导航的「更多」菜单向上弹出 */
.pop-menu.more-menu.up {
  top: auto;
  right: 4px;
  bottom: calc(100% + 8px);
  transform-origin: bottom right;
}
.menu-up-enter-active {
  transition: opacity 0.18s, transform 0.25s var(--ease-spring);
}
.menu-up-leave-active {
  transition: opacity 0.12s;
}
.menu-up-enter-from,
.menu-up-leave-to {
  opacity: 0;
  transform: scale(0.94) translateY(4px);
}
</style>
