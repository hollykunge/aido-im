<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import { Send } from 'lucide-vue-next'

/**
 * 胶囊输入框：默认单行，内容换行后切换为「上方内容 + 底部一行按钮」。
 * 插槽 left：左侧工具按钮；right：发送按钮左边的额外按钮。
 * 事件 keydown 先交给父组件（如 @ 选人面板要接管上下键和回车），父组件 preventDefault 后这里不再处理；
 * 事件 caret 在光标可能移动时触发（输入、点击、方向键），参数为输入框元素。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: String,
  canSend: Boolean,
  flash: Boolean,
  sendIcon: { type: [Object, Function], default: () => Send },
  maxLines: { type: Number, default: 6 },
})
const emit = defineEmits(['update:modelValue', 'submit', 'focus', 'blur', 'keydown', 'caret', 'paste'])

const input = ref(null)
const LINE = 22
const multiline = ref(false)
let singleWidth = 0 // 单行布局下输入区可用宽度，用于判断能否切回单行
let measureCtx
let flips = 0 // 同一轮内布局切换次数，防止意外情况下来回切换

function textWidth(el, text) {
  measureCtx ||= document.createElement('canvas').getContext('2d')
  measureCtx.font = getComputedStyle(el).font
  return measureCtx.measureText(text).width
}
function setMultiline(v) {
  if (multiline.value === v) return
  // 保险：一轮输入内最多切换两次布局
  if (++flips > 2) return
  multiline.value = v
  nextTick(resize)
}
function resize() {
  const el = input.value
  if (!el) return
  const cs = getComputedStyle(el)
  const pad = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom)
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, LINE * props.maxLines + pad) + 'px'
  const text = props.modelValue

  if (!text) {
    // 空内容永远是单行（占位文字折行也不算多行）
    setMultiline(false)
  } else if (!multiline.value) {
    singleWidth = el.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)
    if (el.scrollHeight > LINE + pad + 1) setMultiline(true)
  } else if (!text.includes('\n') && textWidth(el, text) < singleWidth) {
    // 切回单行需要内容在单行宽度下也放得下，避免两种布局来回跳
    setMultiline(false)
  }
}
watch(
  () => props.modelValue,
  () => {
    flips = 0
    nextTick(resize)
  },
)
onMounted(() => {
  flips = 0
  resize()
})

function onKey(e) {
  emit('keydown', e)
  if (e.defaultPrevented) return
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    if (props.canSend) emit('submit')
  }
}

// 在光标处插入文字（表情、@），插入后光标放在新文字之后
function insert(text, replaceFrom) {
  const el = input.value
  const value = props.modelValue
  const start = replaceFrom ?? el?.selectionStart ?? value.length
  const end = el?.selectionEnd ?? value.length
  const next = value.slice(0, start) + text + value.slice(end)
  emit('update:modelValue', next)
  nextTick(() => {
    el?.focus()
    el?.setSelectionRange(start + text.length, start + text.length)
    emit('caret', el)
  })
}

defineExpose({ focus: () => input.value?.focus(), insert, el: input })
</script>

<template>
  <div class="pill" :class="{ multiline, flash }">
    <div class="left"><slot name="left" /></div>
    <textarea
      ref="input"
      :value="modelValue"
      rows="1"
      :placeholder="placeholder"
      @input="emit('update:modelValue', $event.target.value), nextTick(() => emit('caret', input))"
      @keydown="onKey"
      @keyup="['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes($event.key) && emit('caret', input)"
      @click="emit('caret', input)"
      @paste="emit('paste', $event)"
      @focus="emit('focus')"
      @blur="emit('blur')"
    />
    <div class="right"><slot name="right" /></div>
    <button class="send" :class="{ on: canSend }" :disabled="!canSend" title="发送" @click="emit('submit')">
      <component :is="sendIcon" :size="17" />
    </button>
  </div>
</template>

<style scoped>
.pill {
  /* 单行：所有控件在同一行 */
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  grid-template-areas: 'left text right send';
  align-items: end;
  column-gap: 2px;
  padding: 6px;
  border-radius: var(--r-xl);
  background: var(--card);
  box-shadow: 0 0 0 0.5px var(--line-strong), var(--shadow-sm);
  pointer-events: auto;
  transition: box-shadow 0.3s, border-radius 0.25s;
}
.pill.multiline {
  /* 多行：内容占满上方，功能按钮收到底部一行 */
  grid-template-areas:
    'text text text text'
    'left . right send';
  row-gap: 4px;
  padding: 8px 6px 6px;
  border-radius: var(--r-lg);
}
.pill:focus-within {
  box-shadow: 0 0 0 1px var(--accent-ring), var(--shadow-sm);
}
.pill.flash {
  box-shadow: 0 0 0 2px var(--accent), 0 0 0 6px var(--accent-soft);
}
.left,
.right {
  display: flex;
  align-items: flex-end;
  gap: 2px;
}
.left {
  grid-area: left;
}
.right {
  grid-area: right;
}
.left:empty,
.right:empty {
  display: none;
}
textarea {
  grid-area: text;
  min-width: 0;
  height: 36px;
  min-height: 36px;
  padding: 7px 6px;
  border: 0;
  outline: 0;
  resize: none;
  font-size: 15px;
  line-height: 22px;
  background: transparent;
  overflow-y: auto;
}
.multiline textarea {
  padding: 4px 8px;
}
textarea::placeholder {
  color: var(--text-3);
  /* 占位文字不折行，避免撑高输入框 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.send {
  grid-area: send;
  width: 36px;
  height: 36px;
  margin-left: 4px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--fill-muted);
  color: var(--on-accent);
  transition: background 0.2s, transform 0.15s;
}
.send.on {
  background: var(--accent);
  box-shadow: 0 4px 12px color-mix(in srgb, var(--accent) 35%, transparent);
}
.send.on:active {
  transform: scale(0.92);
}
.send:disabled {
  cursor: default;
}
</style>
