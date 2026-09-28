<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { X, Check, Pipette, RotateCcw, Glasses, CloudOff, Sun, Moon, Monitor } from 'lucide-vue-next'
import AgentOrb from '@/components/common/AgentOrb.vue'
import { useAgent } from '@/stores/agent'
import { useWorkspace } from '@/stores/workspace'
import { LOOK_PRESETS, paletteFrom } from '@/agentLook'
import { useTheme } from '@/composables/useTheme'

const emit = defineEmits(['close'])

// 外观：浅色 / 深色 / 跟随系统
const { mode, setMode } = useTheme()
const themes = [
  { key: 'light', label: '浅色', icon: Sun },
  { key: 'dark', label: '深色', icon: Moon },
  { key: 'system', label: '跟随系统', icon: Monitor },
]
const store = useAgent()
const look = computed(() => store.look)

const swatchBg = (p) =>
  `radial-gradient(circle at 30% 28%, ${p.colors[0]}, transparent 60%), radial-gradient(circle at 75% 35%, ${p.colors[1]}, transparent 60%), radial-gradient(circle at 45% 85%, ${p.colors[2]}, transparent 65%), ${p.base}`
const customBg = computed(() => (look.value.custom ? swatchBg(paletteFrom(look.value.custom)) : null))

function pickPreset(key) {
  store.look = { ...look.value, preset: key, custom: null }
}
function pickCustom(e) {
  store.look = { ...look.value, custom: e.target.value }
}
function toggleGlasses() {
  store.look = { ...look.value, glasses: !look.value.glasses }
  store.cheer(900)
}

const ws = useWorkspace()
// 触屏上没有鼠标，形象预览的提示换个说法
const touch = window.matchMedia('(pointer: coarse)').matches

// 手机宽度：从底部弹出的抽屉；按住顶部（把手或标题）往下拖超过一定距离就关闭，没拖够则弹回
const DISMISS_AT = 90
const dragY = ref(0)
let dragStart = null
function onDragStart(e) {
  if (!ws.isCompact || e.target.closest('button')) return
  dragStart = e.clientY
  e.currentTarget.setPointerCapture(e.pointerId)
}
function onDragMove(e) {
  if (dragStart !== null) dragY.value = Math.max(0, e.clientY - dragStart)
}
function onDragEnd() {
  if (dragStart === null) return
  dragStart = null
  if (dragY.value > DISMISS_AT) emit('close')
  else dragY.value = 0
}

const onKey = (e) => e.key === 'Escape' && emit('close')
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="layer" :class="{ sheet: ws.isCompact }" @click.self="emit('close')">
    <section
      class="panel"
      role="dialog"
      aria-label="设置"
      :aria-modal="ws.isCompact"
      :class="{ dragging: dragY > 0 }"
      :style="dragY ? { transform: `translateY(${dragY}px)` } : null"
    >
      <div
        class="drag-zone"
        @pointerdown="onDragStart"
        @pointermove="onDragMove"
        @pointerup="onDragEnd"
        @pointercancel="onDragEnd"
      >
        <span v-if="ws.isCompact" class="grip" aria-hidden="true" />
        <header>
          <h3>设置</h3>
          <button class="icon-btn ghost sm" title="关闭" @click="emit('close')"><X :size="16" /></button>
        </header>
      </div>

      <!-- 外观 -->
      <div class="field first">
        <div class="label">外观</div>
        <div class="seg">
          <button v-for="t in themes" :key="t.key" :class="{ on: mode === t.key }" @click="setMode(t.key)">
            <component :is="t.icon" :size="14" /> {{ t.label }}
          </button>
        </div>
      </div>

      <div class="field">
        <div class="label">{{ store.name }}的形象</div>
      </div>
      <div class="preview">
        <AgentOrb :size="ws.isCompact ? 72 : 92" />
        <span class="muted">{{ touch ? '手指划过试试，它会看着你' : '移动鼠标试试，它会看着你' }}</span>
      </div>

      <div class="field">
        <div class="label">颜色</div>
        <div class="swatches">
          <button
            v-for="p in LOOK_PRESETS"
            :key="p.key"
            class="swatch"
            :class="{ on: !look.custom && look.preset === p.key }"
            :title="p.label"
            @click="pickPreset(p.key)"
          >
            <span class="dot" :style="{ background: swatchBg(p) }">
              <Check v-if="!look.custom && look.preset === p.key" :size="14" />
            </span>
            <span class="name">{{ p.label }}</span>
          </button>

          <!-- 自定义：选一个主色，自动推算整组光斑色 -->
          <label class="swatch" :class="{ on: look.custom }" title="自定义颜色">
            <span class="dot custom" :style="customBg ? { background: customBg } : null">
              <Check v-if="look.custom" :size="14" />
              <Pipette v-else :size="14" />
            </span>
            <span class="name">自定义</span>
            <input type="color" :value="look.custom || '#2f6bf0'" @input="pickCustom" />
          </label>
        </div>
      </div>

      <label class="field row">
        <span class="label-inline"><Glasses :size="16" /> 戴眼镜</span>
        <input type="checkbox" class="switch" :checked="look.glasses" @change="toggleGlasses" />
      </label>

      <!-- 能力：小A的自动化开关 -->
      <div class="field">
        <div class="label">能力</div>
        <label v-for="c in store.capabilities" :key="c.key" class="cap" :title="c.desc">
          <span>{{ c.title }}</span>
          <input :checked="c.on" type="checkbox" class="switch" @change="store.toggleCapability(c, $event.target.checked)" />
        </label>
      </div>

      <!-- 演示：模拟 AI 不可用，检查降级表现 -->
      <label class="field row">
        <span class="label-inline"><CloudOff :size="16" /> 模拟 AI 不可用</span>
        <input type="checkbox" class="switch" :checked="!store.aiOk" @change="store.setAiDown($event.target.checked)" />
      </label>

      <footer>
        <button class="reset" @click="store.resetLook()"><RotateCcw :size="13" /> 恢复默认</button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.layer {
  position: absolute;
  inset: 0;
  z-index: 30;
}
.panel {
  position: absolute;
  top: 38px;
  right: 8px;
  width: min(320px, calc(100% - 24px));
  max-height: calc(100% - 48px);
  overflow-y: auto;
  padding: 14px 16px 12px;
  border-radius: var(--r-lg);
  background: var(--popover);
  box-shadow: var(--shadow-pop), 0 0 0 0.5px var(--line-strong);
  animation: pop 0.3s var(--ease-spring);
  transform-origin: top right;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
h3 {
  margin: 0;
  font-size: 15px;
}
.icon-btn.sm {
  width: 28px;
  height: 28px;
}
.field.first {
  margin-top: 8px;
  border-top: 0;
}
.seg {
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 999px;
  background: var(--seg-track);
  box-shadow: inset 0 0 0 1px var(--seg-border);
}
.seg button {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 30px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-2);
}
.seg button:hover {
  color: var(--text-1);
}
.seg button.on {
  background: var(--seg-active);
  color: var(--text-1);
  font-weight: 600;
  box-shadow: var(--seg-active-shadow);
}
.preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  margin: 0 0 14px;
  padding: 18px 0 12px;
  border-radius: 16px;
  background: var(--workspace);
  font-size: 12px;
}
.field {
  padding: 10px 0;
  border-top: 0.5px solid var(--line);
}
.label {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
}
.swatches {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px 6px;
}
.swatch {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}
.dot {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.5), 0 1px 3px rgba(20, 24, 40, 0.15);
  transition: transform 0.2s var(--ease-spring), box-shadow 0.2s;
}
.dot svg {
  filter: drop-shadow(0 1px 1px rgba(0, 0, 0, 0.3));
}
.swatch:hover .dot {
  transform: scale(1.08);
}
.swatch.on .dot {
  box-shadow: 0 0 0 2px var(--card), 0 0 0 4px var(--text-1);
}
.dot.custom {
  background: conic-gradient(#ff5f7e, #ffd36e, #34c77b, #0ea5e9, #8b7cf6, #ff5f7e);
}
.name {
  font-size: 11px;
  color: var(--text-2);
}
.swatch input[type='color'] {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}
.cap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 0;
  font-size: 13px;
  cursor: pointer;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
}
.label-inline {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}
footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
  border-top: 0.5px solid var(--line);
}
.reset {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border-radius: var(--r-xs);
  font-size: 12px;
  color: var(--text-2);
}
.reset:hover {
  background: var(--hover);
  color: var(--text-1);
}
/* —— 手机宽度：底部抽屉 —— */
.layer.sheet {
  background: var(--scrim);
  animation: fade 0.2s;
}
.sheet .panel {
  top: auto;
  left: 0;
  right: 0;
  bottom: 0;
  width: 100%;
  max-height: 88%;
  padding: 0 16px calc(16px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  transform-origin: bottom center;
  animation: sheet-up 0.32s var(--ease-spring);
  /* 松手没拖够时弹回原位 */
  transition: transform 0.25s var(--ease-spring);
  overscroll-behavior: contain;
}
.sheet .panel.dragging {
  transition: none;
}
.sheet .drag-zone {
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0 -16px;
  padding: 0 16px;
  background: var(--popover);
  /* 在这里拖动是关抽屉，不是滚动页面 */
  touch-action: none;
}
.grip {
  display: block;
  width: 36px;
  height: 5px;
  margin: 8px auto 6px;
  border-radius: 3px;
  background: var(--line-strong);
}
.sheet header {
  padding-bottom: 4px;
}
.sheet h3 {
  font-size: 17px;
}
.sheet .icon-btn.sm {
  width: 36px;
  height: 36px;
}
.sheet .seg button {
  height: 36px;
  font-size: 13px;
}
.sheet .cap,
.sheet .row {
  min-height: 44px;
  font-size: 14px;
}
@keyframes sheet-up {
  from {
    transform: translateY(100%);
  }
}
@keyframes fade {
  from {
    opacity: 0;
  }
}
@keyframes pop {
  from {
    opacity: 0;
    transform: scale(0.92) translateY(-6px);
  }
}
</style>
