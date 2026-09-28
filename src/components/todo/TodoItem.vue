<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Circle, CircleCheck, Sparkles, Check, X, MessageSquareQuote, WandSparkles } from 'lucide-vue-next'
import Avatar from '@/components/common/Avatar.vue'
import { useWorkspace } from '@/stores/workspace'
import { useAgent } from '@/stores/agent'

const props = defineProps({
  todo: { type: Object, required: true },
  focused: Boolean,
})
const router = useRouter()
const ws = useWorkspace()
const agentStore = useAgent()

const src = computed(() => ws.sourceOf(props.todo))
const quote = computed(() => src.value.msg?.text || (src.value.msg?.file ? `[文件] ${src.value.msg.file.name}` : ''))
const flash = computed(() => ws.highlightTodoIds.includes(props.todo.id))
const isOpen = computed(() => props.todo.status === 'open')
const isDone = computed(() => props.todo.status === 'done')
const isSuggested = computed(() => props.todo.status === 'suggested')

// 默认只显示一行，点开才看到原消息、依据和操作
const expanded = ref(false)
const hasMore = computed(() => !!src.value.msg || (agentStore.aiOk && props.todo.note))
watch(
  () => props.focused,
  (f) => f && (expanded.value = true),
  { immediate: true },
)

function openSource() {
  const { convId, msgId } = props.todo.source
  router.push({ path: `/chat/${convId}`, query: { msg: msgId } })
}
function draftReply() {
  ws.agentCollapsed = false
  agentStore.send('帮我起草回复', {
    view: 'chat', convId: props.todo.source.convId, label: `TODO · ${props.todo.title}`,
  })
}
</script>

<template>
  <article :id="`todo-${todo.id}`" class="todo" :class="[todo.status, { flash, focused, expanded }]">
    <button v-if="!isSuggested" class="check" :title="isDone ? '标记为未完成' : '完成'" @click="ws.toggleTodo(todo.id)">
      <CircleCheck v-if="isDone" :size="20" />
      <Circle v-else :size="20" />
    </button>
    <span v-else class="spark" :title="`${agentStore.name}建议`"><Sparkles :size="16" /></span>

    <div class="body" :class="{ clickable: hasMore }" @click="hasMore && (expanded = !expanded)">
      <div class="row">
        <h3>{{ todo.title }}</h3>
        <span class="due" :class="{ soon: todo.today && isOpen }">{{ todo.due }}</span>
      </div>
      <div v-if="src.msg" class="src">{{ src.user?.name }} · {{ src.convName }}</div>

      <div v-if="expanded" class="more" @click.stop>
        <button v-if="src.msg" class="quote" title="查看原消息" @click="openSource">
          <Avatar :user="src.msg.from" :size="18" />
          <span class="quote-text">{{ quote }}</span>
        </button>
        <div v-if="agentStore.aiOk && todo.note && !isDone" class="why"><Sparkles :size="11" /> {{ todo.note }}</div>
        <div v-if="!isSuggested && src.msg" class="actions">
          <button class="act" @click="openSource"><MessageSquareQuote :size="13" /> 查看原消息</button>
          <button v-if="agentStore.aiOk && todo.kind === 'reply' && isOpen" class="act ai" @click="draftReply">
            <WandSparkles :size="13" /> 让{{ agentStore.name }}起草回复
          </button>
        </div>
      </div>
    </div>

    <div v-if="isSuggested" class="decide">
      <button class="icon-btn ghost ok" title="加入 TODO" @click="ws.acceptTodos([todo.id])"><Check :size="16" /></button>
      <button class="icon-btn ghost" title="忽略" @click="ws.dismissTodos([todo.id])"><X :size="16" /></button>
    </div>
  </article>
</template>

<style scoped>
.todo {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 14px;
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: var(--shadow-sm), 0 0 0 0.5px var(--line);
  transition: box-shadow 0.2s, opacity 0.2s;
}
.todo:hover {
  box-shadow: var(--shadow-md), 0 0 0 0.5px var(--line-strong);
}
.todo.flash,
.todo.focused {
  animation: flash 2.2s ease-out;
}
.todo.suggested {
  background: var(--panel);
}
.check {
  flex: none;
  height: 22px;
  color: var(--text-3);
}
.check:hover,
.done .check {
  color: var(--accent);
}
.spark {
  flex: none;
  width: 20px;
  height: 22px;
  display: grid;
  place-items: center;
  color: var(--accent);
}
.body {
  flex: 1;
  min-width: 0;
}
.body.clickable {
  cursor: pointer;
}
.row {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
h3 {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
}
.done h3 {
  text-decoration: line-through;
  color: var(--text-3);
}
.due {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}
.due.soon {
  color: var(--danger-text);
  font-weight: 600;
}
.src {
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.more {
  margin-top: 8px;
  cursor: default;
}
.quote {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  background: var(--card-sub);
  text-align: left;
  transition: background 0.15s;
}
.quote:hover {
  background: var(--hover-strong);
}
.quote-text {
  font-size: 13px;
  color: var(--text-2);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.why {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--accent-strong);
}
.why svg {
  flex: none;
  margin-top: 3px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: 6px -8px 0;
}
.act {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 8px;
  border-radius: var(--r-xs);
  font-size: 12px;
  color: var(--text-2);
}
.act:hover {
  background: var(--hover);
  color: var(--text-1);
}
.act.ai {
  color: var(--accent-strong);
}

.decide {
  flex: none;
  display: flex;
  gap: 2px;
  margin: -4px -6px -4px 0;
}
.decide .icon-btn {
  width: 30px;
  height: 30px;
}
/* 手机宽度：按手指的大小给足点击区域（约 40px），图标本身大小不变 */
@media (max-width: 760px) {
  .check,
  .spark {
    width: 40px;
    height: 40px;
    margin: -9px -10px -9px -12px;
    display: grid;
    place-items: center;
  }
  .decide .icon-btn {
    width: 40px;
    height: 40px;
  }
  .decide {
    margin: -5px -8px -5px 0;
  }
}
.decide .ok {
  color: var(--accent-strong);
}

@keyframes flash {
  0%,
  30% {
    box-shadow: 0 0 0 2px var(--accent), 0 0 0 7px var(--accent-soft);
  }
}
</style>
