<script setup>
import { onMounted, ref } from 'vue'
import { Lock, Pencil, Trash2, Plus, Check, X, MessageCircle, ListTodo, Calendar, FileText, Mail } from 'lucide-vue-next'
import AgentOrb from '@/components/common/AgentOrb.vue'
import { useWorkspace } from '@/stores/workspace'
import { useAgent } from '@/stores/agent'
import { useMemory } from '@/stores/memory'

const ws = useWorkspace()
const agentStore = useAgent()
// 数据在 store 里：其他标签页改了记忆，推送来的快照会直接反映到这里
const memory = useMemory()
const sourceIcon = { chat: MessageCircle, todo: ListTodo, calendar: Calendar, docs: FileText, mail: Mail }

onMounted(() => memory.load())

// 输入框出现时自动聚焦，光标放到末尾
const vFocus = {
  mounted(el) {
    el.focus()
    el.setSelectionRange?.(el.value.length, el.value.length)
  },
}
// 中文输入法按回车是确认候选词，这时不能当成提交
const isEnter = (e) => !e.isComposing && e.keyCode !== 229

// —— 修改记忆：原地编辑，回车保存，Esc 取消 ——
const editingId = ref(null)
const editText = ref('')
function startEdit(m) {
  editingId.value = m.id
  editText.value = m.text
}
function cancelEdit() {
  editingId.value = null
}
async function saveEdit(m) {
  const text = editText.value.trim()
  if (!text || text === m.text) return cancelEdit()
  // 保存失败时留在编辑状态，内容不丢
  if (await memory.editItem(m, text)) cancelEdit()
}

// —— 添加画像标签：点「+」出现输入框，回车或失焦保存，空着失焦即取消 ——
const addingGroup = ref(null)
const newTag = ref('')
let savingTag = false
function startTag(g) {
  addingGroup.value = g.group
  newTag.value = ''
}
// 先清空内容：Esc 移除输入框时如果再触发失焦，不会被当成保存
function cancelTag() {
  newTag.value = ''
  addingGroup.value = null
}
async function saveTag(g) {
  const tag = newTag.value.trim()
  if (!tag) return cancelTag()
  // 回车保存后输入框失焦会再触发一次，忽略
  if (savingTag) return
  savingTag = true
  const ok = await memory.addTag(g, tag)
  savingTag = false
  if (ok) cancelTag()
}
function ask() {
  ws.agentCollapsed = false
  agentStore.send('你还记得我哪些工作习惯？', { view: 'memory', label: '记忆' })
}
</script>

<template>
  <div class="page" :class="{ compact: ws.isCompact }">
    <section class="hero">
      <AgentOrb :size="ws.isCompact ? 40 : 56" />
      <div class="hero-main">
        <h1>{{ agentStore.name }}记住的关于你</h1>
        <p><Lock :size="13" /> 仅你可见。记忆只用于更好地帮你，你可以随时修改或删除。</p>
      </div>
      <label class="learn">
        <span>从日常工作中学习</span>
        <input :checked="memory.learning" type="checkbox" class="switch" @change="memory.setLearning($event.target.checked)" />
      </label>
    </section>

    <section class="card">
      <h2>画像</h2>
      <div v-for="g in memory.profile" :key="g.group" class="group">
        <div class="group-name">{{ g.group }}</div>
        <div class="tags">
          <span v-for="t in g.tags" :key="t.id" class="tag">{{ t.tag }}</span>
          <input
            v-if="addingGroup === g.group"
            v-model="newTag"
            v-focus
            class="tag tag-input"
            maxlength="40"
            :placeholder="`添加${g.group}`"
            @keydown.enter="isEnter($event) && saveTag(g)"
            @keydown.esc="cancelTag"
            @blur="saveTag(g)"
          />
          <button v-else class="tag add" :title="`添加${g.group}`" @click="startTag(g)"><Plus :size="13" /></button>
        </div>
      </div>
      <p class="note">这些不是空闲时间的「顺便」，是你认真投入的结果——{{ agentStore.name }}会按这些优先级帮你筛信息。</p>
      <button v-if="agentStore.aiOk" class="btn btn-primary btn-lg" @click="ask">展开聊聊</button>
    </section>

    <section class="card">
      <h2>具体记忆 <span class="muted">{{ memory.items.length }}</span></h2>
      <TransitionGroup name="fade" tag="div" class="items">
        <div v-for="m in memory.items" :key="m.id" class="item">
          <div class="item-main">
            <textarea
              v-if="editingId === m.id"
              v-model="editText"
              v-focus
              class="item-edit"
              rows="1"
              maxlength="500"
              @keydown.enter.exact="isEnter($event) && ($event.preventDefault(), saveEdit(m))"
              @keydown.esc="cancelEdit"
            />
            <div v-else class="item-text">{{ m.text }}</div>
            <div class="item-meta">{{ m.source }} · {{ m.date }}</div>
          </div>
          <template v-if="editingId === m.id">
            <button class="icon-btn ghost sm ok" title="保存（回车）" @click="saveEdit(m)"><Check :size="16" /></button>
            <button class="icon-btn ghost sm" title="取消（Esc）" @click="cancelEdit"><X :size="16" /></button>
          </template>
          <template v-else>
            <button class="icon-btn ghost sm" title="修改" @click="startEdit(m)"><Pencil :size="15" /></button>
            <button class="icon-btn ghost sm danger" title="删除" @click="memory.removeItem(m.id)"><Trash2 :size="15" /></button>
          </template>
        </div>
      </TransitionGroup>
    </section>

    <section class="card">
      <h2>{{ agentStore.name }}可以读取</h2>
      <div class="sources">
        <label v-for="s in memory.sources" :key="s.key" class="source">
          <span class="src-ic" :class="{ on: s.on }"><component :is="sourceIcon[s.key]" :size="18" /></span>
          <span class="src-body">
            <b>{{ s.label }}</b>
            <span class="muted">{{ s.desc }}</span>
          </span>
          <input :checked="s.on" type="checkbox" class="switch" @change="memory.toggleSource(s, $event.target.checked)" />
        </label>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  height: 100%;
  overflow-y: auto;
  padding: 24px 28px 90px;
  /* 顶部边缘渐进透明：往上滚的内容在顶端淡出 */
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, #000 24px);
  mask-image: linear-gradient(to bottom, transparent 0, #000 24px);
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page > * {
  width: 100%;
  max-width: 900px;
  margin: 0 auto;
}
.hero {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 24px 26px;
  border-radius: var(--r-xl);
  background: var(--hero);
  box-shadow: var(--shadow-md);
}
.hero-main {
  flex: 1;
}
h1 {
  margin: 0 0 4px;
  font-size: 24px;
}
.hero p {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 13px;
  color: var(--text-2);
}
.learn {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px 8px 14px;
  border-radius: 14px;
  background: var(--card-sub);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.card {
  padding: 20px 22px;
  border-radius: var(--r-xl);
  background: var(--card-glass);
  box-shadow: var(--shadow-md);
}
h2 {
  display: flex;
  gap: 8px;
  margin: 0 0 12px;
  font-size: 17px;
}
.group {
  padding: 12px 16px;
  border-radius: 14px;
  background: var(--card-sub);
}
.group + .group {
  margin-top: 8px;
}
.group-name {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--text-3);
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tag.add {
  background: transparent;
  box-shadow: inset 0 0 0 1px var(--line-strong);
  color: var(--text-3);
}
.tag-input {
  width: 9em;
  /* 支持的浏览器里随内容变宽 */
  field-sizing: content;
  min-width: 6em;
  max-width: 100%;
  border: 0;
  outline: none;
  background: var(--card);
  color: var(--text-1);
  box-shadow: inset 0 0 0 1.5px var(--accent);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
}
.tag-input::placeholder {
  color: var(--text-3);
  font-weight: 400;
}
.note {
  margin: 16px 0;
  font-size: 14px;
  color: var(--text-2);
}

.items {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: var(--card-sub);
  overflow: hidden;
}
.item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 10px 12px 16px;
}
.item + .item {
  border-top: 0.5px solid var(--line);
}
.item-main {
  flex: 1;
}
.item-text {
  font-size: 14px;
}
.item-edit {
  display: block;
  width: calc(100% + 8px);
  margin: -4px 0 2px -8px;
  padding: 4px 8px;
  border: 0;
  border-radius: 8px;
  outline: none;
  resize: none;
  field-sizing: content;
  background: var(--card);
  box-shadow: inset 0 0 0 1.5px var(--accent);
  color: var(--text-1);
  font: inherit;
  font-size: 14px;
  line-height: 1.5;
}
.icon-btn.ok {
  color: var(--accent-strong);
}
.item-meta {
  font-size: 12px;
  color: var(--text-3);
}
.icon-btn.sm {
  width: 30px;
  height: 30px;
}
.icon-btn.danger:hover {
  color: var(--danger);
  background: var(--danger-soft);
}

.sources {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.source {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--card-sub);
  cursor: pointer;
}
.src-ic {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--fill-muted);
  color: var(--text-3);
}
.src-ic.on {
  background: var(--accent-soft);
  color: var(--accent-strong);
}
.src-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  font-size: 12px;
}
.src-body b {
  font-size: 14px;
  font-weight: 600;
}

/* —— 响应式：按主窗口宽度 —— */
/* —— 手机宽度：头部收紧、少一层内边距，按钮按手指大小给足点击区域 —— */
.page.compact {
  padding: 12px 12px 24px;
  gap: 10px;
}
.compact .hero {
  gap: 12px;
  padding: 14px;
}
.compact .hero-main {
  flex-basis: calc(100% - 56px);
}
.compact h1 {
  font-size: 18px;
}
.compact .hero p {
  font-size: 12px;
}
.compact .card {
  padding: 14px 12px;
}
.compact h2 {
  margin-bottom: 10px;
  font-size: 16px;
}
.compact .group {
  padding: 10px;
}
.compact .tags {
  gap: 6px;
}
.compact .tag {
  height: 32px;
  padding: 0 12px;
}
.compact .tag.add {
  min-width: 40px;
  justify-content: center;
}
.compact .note {
  margin: 12px 0;
  font-size: 13px;
}
.compact .item {
  gap: 2px;
  padding: 10px 4px 10px 12px;
}
.compact .item .icon-btn.sm {
  width: 40px;
  height: 40px;
}

@container ws (max-width: 640px) {
  .page {
    padding: 24px 12px 40px;
    gap: 12px;
  }
  .hero {
    flex-wrap: wrap;
    padding: 18px 16px;
  }
  .hero-main {
    flex-basis: calc(100% - 80px);
  }
  .learn {
    width: 100%;
    justify-content: space-between;
  }
  .card {
    padding: 18px 16px;
  }
  .sources {
    grid-template-columns: 1fr;
  }
}
</style>
