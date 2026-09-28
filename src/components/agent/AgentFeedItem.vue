<script setup>
import { useRouter } from 'vue-router'
import {
  Check, ChevronRight, FileText, CircleCheck, PenLine, Send, LoaderCircle, Circle, Link2, CircleX,
} from 'lucide-vue-next'
import { useAgent } from '@/stores/agent'
import { useWorkspace } from '@/stores/workspace'

const props = defineProps({ item: { type: Object, required: true } })
const router = useRouter()
const agent = useAgent()
const ws = useWorkspace()

function go(to) {
  if (!to) return
  ws.revealWorkspace()
  router.push(to)
}
function fillDraft() {
  const convId = props.item.convId
  agent.insertDraft(props.item)
  go(`/chat/${convId}`)
}
async function sendDraft() {
  const convId = props.item.convId
  await agent.sendDraft(props.item)
  go(`/chat/${convId}`)
}

// —— 提议卡片：引用 TODO 中的项，状态实时读取 ——
const todosOf = (it) => it.todoIds.map((id) => ws.getTodo(id)).filter(Boolean)
const idsWith = (it, status) => todosOf(it).filter((t) => t.status === status).map((t) => t.id)
function proposalState(it) {
  const ts = todosOf(it)
  if (ts.some((t) => t.status === 'suggested')) return 'pending'
  return ts.every((t) => t.status === 'dismissed') ? 'dismissed' : 'accepted'
}
const acceptedCount = (it) => todosOf(it).filter((t) => t.status === 'open' || t.status === 'done').length
function focusTodo(id) {
  go({ path: '/todo', query: { focus: id } })
}
</script>

<template>
  <!-- 状态行：任务已完成 -->
  <button v-if="item.type === 'status'" class="status-line">
    <Check :size="16" /> {{ item.text }} <ChevronRight :size="15" />
  </button>

  <!-- 用户消息 -->
  <div v-else-if="item.type === 'user'" class="user-wrap">
    <span v-if="item.context" class="ctx"><Link2 :size="12" /> {{ item.context }}</span>
    <div class="user-bubble">{{ item.text }}</div>
  </div>

  <!-- Agent 文字回复 -->
  <div v-else-if="item.type === 'agent'" class="bubble">
    <p v-for="(p, i) in item.paragraphs" :key="i">{{ p }}</p>
    <ul v-if="item.bullets" class="bullets">
      <li v-for="(b, i) in item.bullets" :key="i" :class="{ link: b.link }" @click="go(b.link)">
        <strong v-if="b.strong">{{ b.strong }}</strong>{{ b.text }}
        <ChevronRight v-if="b.link" :size="14" class="li-go" />
      </li>
    </ul>
    <p v-if="item.tail">{{ item.tail }}</p>
  </div>

  <!-- 思考中 -->
  <div v-else-if="item.type === 'thinking'" class="bubble thinking">
    <div v-for="(s, i) in item.steps" :key="i" class="step" :class="{ done: i < item.current, cur: i === item.current }">
      <CircleCheck v-if="i < item.current" :size="15" />
      <LoaderCircle v-else-if="i === item.current" :size="15" class="spin" />
      <Circle v-else :size="15" />
      {{ s }}
    </div>
  </div>

  <!-- 文档卡片 -->
  <button v-else-if="item.type === 'doc'" class="doc" @click="go(item.to)">
    <span class="doc-ic"><FileText :size="20" /></span>
    <span class="doc-body">
      <span class="doc-title">{{ item.title }}</span>
      <span class="doc-desc">{{ item.desc }}</span>
    </span>
    <ChevronRight :size="18" class="muted" />
  </button>

  <!-- 行动建议：从消息中识别的 TODO，需要用户确认 -->
  <div v-else-if="item.type === 'proposal'" class="bubble proposal">
    <p>{{ item.text }}</p>
    <div class="prop-list">
      <button v-for="t in todosOf(item)" :key="t.id" class="prop-item" :class="t.status" @click="focusTodo(t.id)">
        <CircleCheck v-if="t.status === 'open' || t.status === 'done'" :size="16" class="ok" />
        <CircleX v-else-if="t.status === 'dismissed'" :size="16" class="muted" />
        <Circle v-else :size="16" class="muted" />
        <span class="prop-body">
          <span class="prop-title">{{ t.title }}</span>
          <span class="prop-meta">来自「{{ ws.sourceOf(t).convName }}」· {{ t.due }}</span>
        </span>
        <ChevronRight :size="14" class="muted" />
      </button>
    </div>
    <div v-if="proposalState(item) === 'pending'" class="actions">
      <button class="btn btn-primary" @click="agent.acceptProposal({ todoIds: idsWith(item, 'suggested') })">全部加入 TODO</button>
      <button class="btn btn-plain" @click="agent.dismissProposal({ todoIds: idsWith(item, 'suggested') })">暂不需要</button>
    </div>
    <button v-else-if="proposalState(item) === 'accepted'" class="done-line" @click="go('/todo')">
      <Check :size="15" /> 已加入 {{ acceptedCount(item) }} 项 TODO · 去看看 <ChevronRight :size="14" />
    </button>
    <div v-else class="done-line muted">已忽略，我不会再提醒</div>
  </div>

  <!-- 回复草稿：可填入主窗口聊天输入框 -->
  <div v-else-if="item.type === 'draft'" class="draft">
    <div class="draft-head"><PenLine :size="14" /> 草稿 · 发往「{{ item.convName }}」</div>
    <div class="draft-text">{{ item.text }}</div>
    <div v-if="item.state === 'pending'" class="actions">
      <button class="btn btn-soft" @click="fillDraft"><PenLine :size="14" /> 填入输入框</button>
      <button class="btn btn-primary" @click="sendDraft"><Send :size="14" /> 直接发送</button>
    </div>
    <div v-else class="done-line">
      <Check :size="15" />
      <template v-if="item.state === 'sent'">已发送<template v-if="item.doneTodo"> · 已勾掉 TODO「{{ item.doneTodo }}」</template></template>
      <template v-else>已填入聊天输入框</template>
    </div>
  </div>

</template>

<style scoped>
.status-line {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px;
  font-size: 14px;
  color: var(--text-2);
  border-radius: var(--r-xs);
}
.status-line:hover {
  background: var(--hover);
}

.bubble {
  align-self: stretch;
  padding: 14px 18px;
  border-radius: 18px;
  background: var(--card);
  /* 窗口为纯白 / 纯黑，用淡描边区分卡片 */
  box-shadow: var(--shadow-sm), 0 0 0 0.5px var(--line-strong);
  font-size: 15px;
  line-height: 1.75;
  animation: rise 0.35s var(--ease-spring);
}
.bubble p {
  margin: 0 0 8px;
}
.bubble p:last-child {
  margin-bottom: 0;
}
.bullets {
  margin: 4px 0 10px;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.bullets li {
  position: relative;
  padding: 6px 26px 6px 22px;
  border-radius: var(--r-sm);
}
.bullets li::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 17px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}
.bullets li.link {
  cursor: pointer;
}
.bullets li.link:hover {
  background: var(--accent-softer);
}
.bullets strong {
  font-weight: 600;
}
.li-go {
  position: absolute;
  right: 6px;
  top: 12px;
  color: var(--text-3);
}

.user-wrap {
  align-self: flex-end;
  max-width: 82%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  animation: rise 0.3s var(--ease-spring);
}
.ctx {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-3);
}
.user-bubble {
  padding: 10px 16px;
  border-radius: 18px 18px 6px 18px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 90%, #fff), color-mix(in srgb, var(--accent) 92%, #000));
  color: var(--on-accent);
  font-size: 15px;
  box-shadow: 0 4px 14px var(--accent-line);
}

.thinking {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  color: var(--text-3);
}
.step {
  display: flex;
  align-items: center;
  gap: 8px;
  transition: color 0.2s;
}
.step.done {
  color: var(--text-2);
}
.step.done svg {
  color: var(--accent);
}
.step.cur {
  color: var(--text-1);
}
.spin {
  color: var(--blue);
  animation: spin 0.9s linear infinite;
}

.doc {
  align-self: flex-start;
  width: 86%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 16px;
  background: var(--card);
  /* 窗口为纯白 / 纯黑，用淡描边区分卡片 */
  box-shadow: var(--shadow-sm), 0 0 0 0.5px var(--line-strong);
  text-align: left;
  transition: box-shadow 0.15s;
}
.doc:hover {
  box-shadow: var(--shadow-md);
}
.doc-ic {
  width: 42px;
  height: 42px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: var(--r-sm);
  background: var(--blue-soft);
  color: var(--blue);
}
.doc-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.doc-title {
  font-size: 15px;
  font-weight: 500;
}
.doc-desc {
  font-size: 13px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.prop-list {
  margin: 10px 0 12px;
  border-radius: 12px;
  background: var(--card-sub);
  overflow: hidden;
}
.prop-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  font-size: 14px;
  text-align: left;
}
.prop-item:hover {
  background: var(--hover);
}
.prop-item.dismissed .prop-title {
  text-decoration: line-through;
  color: var(--text-3);
}
.prop-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.45;
}
.prop-item + .prop-item {
  border-top: 0.5px solid var(--line);
}
.prop-title {
  font-weight: 500;
}
.prop-meta {
  font-size: 12px;
  color: var(--text-3);
}
.ok {
  color: var(--accent);
}
.actions {
  display: flex;
  gap: 8px;
}
.done-line {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--accent-strong);
}

.draft {
  align-self: stretch;
  padding: 12px 14px 14px;
  border-radius: 18px;
  background: linear-gradient(180deg, var(--accent-softer), var(--card));
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--accent) 22%, transparent);
  animation: rise 0.35s var(--ease-spring);
}
.draft-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-strong);
}
.draft-text {
  margin: 8px 0 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--card);
  box-shadow: 0 0 0 0.5px var(--line-strong);
  font-size: 15px;
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
