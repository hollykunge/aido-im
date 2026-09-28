<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { X, KeyRound, ChevronRight, CircleCheck, LoaderCircle, Pencil, Check, Pipette } from 'lucide-vue-next'
import Avatar from '@/components/common/Avatar.vue'
import { useWorkspace } from '@/stores/workspace'
import { useRealtime } from '@/stores/realtime'
import { api } from '@/api'
import { stamp } from '@/api/format'

const emit = defineEmits(['close'])
const ws = useWorkspace()
const realtime = useRealtime()

const PRESENCE = { open: '在线', connecting: '连接中…', offline: '离线，正在重连' }
const rows = computed(() => [
  { label: '账号', value: ws.me?.login },
  { label: '部门', value: ws.me?.dept },
  { label: '职位', value: ws.me?.role },
  { label: '本次登录', value: ws.me?.loggedInAt && stamp(ws.me.loggedInAt) },
])

// 输入框出现时自动聚焦并全选
const vFocus = { mounted: (el) => (el.focus(), el.select()) }
// 中文输入法按回车是确认候选词，这时不能当成提交
const isEnter = (e) => !e.isComposing && e.keyCode !== 229

// —— 姓名：点铅笔原地编辑，回车或失焦保存，Esc 取消 ——
const NAME_MAX = 20
const editingName = ref(false)
const nameDraft = ref('')
const nameError = ref('')
let savingName = false
function startName() {
  nameDraft.value = ws.me?.name || ''
  nameError.value = ''
  editingName.value = true
}
function cancelName() {
  nameDraft.value = ws.me?.name || ''
  editingName.value = false
}
async function saveName() {
  const name = nameDraft.value.trim()
  if (savingName) return
  if (!name) {
    nameError.value = '姓名不能为空'
    return
  }
  if (name === ws.me?.name) return cancelName()
  savingName = true
  const ok = await ws.updateProfile({ name })
  savingName = false
  if (ok) {
    editingName.value = false
    nameError.value = ''
  }
}

// —— 头像颜色：点色块立即保存；也可以自选颜色 ——
const COLORS = ['#2f6bf0', '#12b5a0', '#0ea5e9', '#6366f1', '#a855f7', '#ec4899', '#ef4444', '#f97316', '#f59e0b', '#84cc16', '#64748b']
const color = computed(() => ws.users[ws.meId]?.color)
const isCustom = computed(() => color.value && !COLORS.includes(color.value))
function pickColor(c) {
  if (c.toLowerCase() !== color.value) ws.updateProfile({ color: c.toLowerCase() })
}

// —— 修改密码：点开后原地展开表单 ——
const MIN_LENGTH = 8
const editing = ref(false)
const form = ref({ current: '', next: '', confirm: '' })
const pwError = ref('')
const saving = ref(false)
const done = ref(false)
const currentInput = ref(null)
function startChange() {
  editing.value = true
  done.value = false
  pwError.value = ''
  form.value = { current: '', next: '', confirm: '' }
  nextTick(() => currentInput.value?.focus())
}
function cancelChange() {
  editing.value = false
}
// 前端先挡掉明显的问题，其余规则（常见弱密码、不能含账号名等）以后端为准
function check() {
  const { current, next, confirm } = form.value
  if (!current || !next) return '请填写当前密码和新密码'
  if (next.length < MIN_LENGTH) return `新密码至少 ${MIN_LENGTH} 位`
  if (next === current) return '新密码不能和当前密码相同'
  if (next !== confirm) return '两次输入的新密码不一致'
  return ''
}
async function submitChange() {
  if (saving.value) return
  pwError.value = check()
  if (pwError.value) return
  saving.value = true
  try {
    await api.changePassword(form.value.current, form.value.next)
    editing.value = false
    done.value = true
  } catch (e) {
    pwError.value = e.message
  } finally {
    saving.value = false
  }
}

// 打开时把焦点放到关闭按钮，Esc 关闭，方便键盘操作
const closeBtn = ref(null)
const onKey = (e) => e.key === 'Escape' && emit('close')
onMounted(() => {
  closeBtn.value?.focus()
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="layer" @click.self="emit('close')">
    <section class="panel" role="dialog" aria-modal="true" aria-labelledby="profile-title">
      <header>
        <h3 id="profile-title">个人信息</h3>
        <button ref="closeBtn" class="icon-btn ghost sm" title="关闭" @click="emit('close')"><X :size="16" /></button>
      </header>

      <div class="hero">
        <span class="avatar-wrap">
          <Avatar :user="ws.meId" :size="64" />
          <span class="dot" :class="realtime.status" />
        </span>
        <div v-if="editingName" class="name-edit">
          <input
            v-model="nameDraft"
            v-focus
            :maxlength="NAME_MAX"
            aria-label="姓名"
            @keydown.enter="isEnter($event) && saveName()"
            @keydown.esc.stop="cancelName"
            @blur="saveName"
          />
          <button class="icon-btn ghost sm ok" title="保存（回车）" @mousedown.prevent @click="saveName">
            <Check :size="16" />
          </button>
        </div>
        <div v-else class="name-row">
          <b class="name">{{ ws.me?.name }}</b>
          <button class="icon-btn ghost sm" title="修改姓名" @click="startName"><Pencil :size="14" /></button>
        </div>
        <span v-if="nameError" class="name-error" role="alert">{{ nameError }}</span>
        <span v-else class="presence">{{ PRESENCE[realtime.status] }}</span>
      </div>

      <div class="colors">
        <span class="colors-label">头像颜色</span>
        <div class="swatches" role="radiogroup" aria-label="头像颜色">
          <button
            v-for="c in COLORS"
            :key="c"
            class="swatch"
            :class="{ on: c === color }"
            :style="{ background: c }"
            role="radio"
            :aria-checked="c === color"
            :title="c"
            @click="pickColor(c)"
          />
          <label class="swatch custom" :class="{ on: isCustom }" :style="isCustom ? { background: color } : null" title="自选颜色">
            <Pipette v-if="!isCustom" :size="13" />
            <input type="color" :value="color || '#2f6bf0'" @change="pickColor($event.target.value)" />
          </label>
        </div>
      </div>

      <dl class="rows">
        <template v-for="r in rows" :key="r.label">
          <dt>{{ r.label }}</dt>
          <dd>{{ r.value || '—' }}</dd>
        </template>
      </dl>
      <p class="note">部门和职位由管理员维护。</p>

      <!-- 修改密码 -->
      <div class="security">
        <button v-if="!editing" class="row-btn" @click="startChange">
          <KeyRound :size="16" />
          <span>修改密码</span>
          <ChevronRight :size="16" class="chev" />
        </button>
        <form v-else class="pw-form" novalidate @submit.prevent="submitChange">
          <!-- 给密码管理器看的：告诉它改的是哪个账号的密码 -->
          <input type="text" autocomplete="username" :value="ws.me?.login" hidden readonly />
          <label>
            <span>当前密码</span>
            <input ref="currentInput" v-model="form.current" type="password" autocomplete="current-password" :disabled="saving" />
          </label>
          <label>
            <span>新密码</span>
            <input v-model="form.next" type="password" autocomplete="new-password" :disabled="saving" :placeholder="`至少 ${MIN_LENGTH} 位`" />
          </label>
          <label>
            <span>确认新密码</span>
            <input v-model="form.confirm" type="password" autocomplete="new-password" :disabled="saving" />
          </label>
          <p class="pw-error" role="alert">{{ pwError }}</p>
          <div class="pw-actions">
            <button type="button" class="btn btn-plain" :disabled="saving" @click="cancelChange">取消</button>
            <button type="submit" class="btn btn-primary" :disabled="saving">
              <LoaderCircle v-if="saving" :size="14" class="spin" />
              {{ saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </form>
        <p v-if="done" class="pw-done" role="status">
          <CircleCheck :size="14" /> 密码已修改，其他设备上的登录已退出
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.layer {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 16px;
  background: color-mix(in srgb, var(--window) 35%, transparent);
  animation: fade 0.2s;
}
.panel {
  width: min(340px, 100%);
  max-height: 100%;
  overflow-y: auto;
  padding: 14px 16px 16px;
  border-radius: var(--r-lg);
  background: var(--popover);
  box-shadow: var(--shadow-pop), 0 0 0 0.5px var(--line-strong);
  animation: pop 0.3s var(--ease-spring);
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
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  margin: 12px 0 16px;
  padding: 20px 0 16px;
  border-radius: 16px;
  background: var(--workspace);
}
.avatar-wrap {
  position: relative;
  margin-bottom: 8px;
}
.dot {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--online);
  box-shadow: 0 0 0 3px var(--workspace);
}
.dot.connecting {
  background: var(--away);
}
.dot.offline {
  background: var(--text-3);
}
.name {
  font-size: 18px;
}
.presence {
  font-size: 12px;
  color: var(--text-3);
}
.name-row {
  display: flex;
  align-items: center;
  gap: 2px;
  /* 铅笔按钮不参与居中，姓名本身保持正中 */
  margin-right: -30px;
}
.name-edit {
  display: flex;
  align-items: center;
  gap: 4px;
}
.name-edit input {
  width: 160px;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 9px;
  outline: none;
  background: var(--card);
  box-shadow: inset 0 0 0 1.5px var(--accent);
  color: var(--text-1);
  font-size: 15px;
  font-weight: 600;
  text-align: center;
}
.icon-btn.ok {
  color: var(--accent-strong);
}
.name-error {
  font-size: 12px;
  color: var(--danger);
}
.colors {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 4px 16px;
}
.colors-label {
  font-size: 12px;
  color: var(--text-3);
}
.swatches {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px 4px;
}
.swatch {
  position: relative;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  box-shadow: inset 0 0 0 0.5px rgba(0, 0, 0, 0.12);
  transition: transform 0.15s var(--ease-spring), box-shadow 0.15s;
  cursor: pointer;
}
.swatch:hover {
  transform: scale(1.1);
}
.swatch.on {
  box-shadow: 0 0 0 2px var(--popover), 0 0 0 4px var(--text-1);
}
.swatch.custom {
  background: var(--fill-muted);
  color: var(--text-2);
}
.swatch.custom input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
.rows {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 10px 16px;
  margin: 0;
  padding: 0 4px;
  font-size: 14px;
}
dt {
  color: var(--text-3);
}
dd {
  margin: 0;
  color: var(--text-1);
  text-align: right;
  word-break: break-all;
}
.note {
  margin: 16px 4px 0;
  font-size: 12px;
  color: var(--text-3);
}
.security {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 0.5px solid var(--line);
}
.row-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  padding: 0 8px;
  border-radius: var(--r-sm);
  font-size: 14px;
  color: var(--text-1);
}
.row-btn:hover {
  background: var(--hover);
}
.row-btn svg {
  color: var(--text-2);
}
.row-btn .chev {
  margin-left: auto;
  color: var(--text-3);
}
.pw-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 2px 4px 0;
}
.pw-form label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12px;
  color: var(--text-2);
}
.pw-form input {
  height: 36px;
  padding: 0 10px;
  border: 0;
  border-radius: 9px;
  outline: none;
  background: var(--card-sub);
  box-shadow: inset 0 0 0 1px var(--line-strong);
  color: var(--text-1);
  font-size: 14px;
}
.pw-form input:focus {
  box-shadow: inset 0 0 0 1.5px var(--accent);
}
.pw-error {
  min-height: 16px;
  margin: 0;
  font-size: 12px;
  color: var(--danger);
}
.pw-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.pw-actions .btn:disabled {
  opacity: 0.7;
  cursor: default;
}
.pw-done {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 10px 4px 0;
  font-size: 12px;
  color: var(--accent-strong);
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
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
    transform: scale(0.96);
  }
}
</style>
