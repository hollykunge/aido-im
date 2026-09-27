<script setup>
import { computed, ref, watch } from 'vue'
import { Plus, Mic, ArrowUp, Eye, X } from 'lucide-vue-next'
import PillComposer from '@/components/common/PillComposer.vue'
import { useAgent } from '@/stores/agent'

const props = defineProps({ context: Object, busy: Boolean })
const emit = defineEmits(['send', 'detach'])

const text = ref('')
const canSend = computed(() => !!text.value.trim() && !props.busy)

// 用户输入时，虚拟形象进入「倾听」
const store = useAgent()
const focused = ref(false)
watch([text, focused], ([t, f]) => (store.typing = f && !!t.trim()))

function submit() {
  if (!canSend.value) return
  emit('send', text.value)
  text.value = ''
}
</script>

<template>
  <div class="agent-composer">
    <!-- 上下文：在输入框上方 -->
    <div v-if="context" class="ctx">
      <Eye :size="13" />
      <span>{{ store.name }}能看到：<b>{{ context.label }}</b></span>
      <button class="ctx-x" title="不带上下文" @click="emit('detach')"><X :size="12" /></button>
    </div>

    <PillComposer
      v-model="text"
      :placeholder="`和${store.name}聊天，或让它帮你做事`"
      :can-send="canSend"
      :send-icon="ArrowUp"
      @submit="submit"
      @focus="focused = true"
      @blur="focused = false"
    >
      <template #left>
        <button class="icon-btn ghost tool" title="添加文件或引用"><Plus :size="20" /></button>
      </template>
      <template #right>
        <button class="icon-btn ghost tool" title="语音输入"><Mic :size="19" /></button>
      </template>
    </PillComposer>
  </div>
</template>

<style scoped>
.agent-composer {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.agent-composer > :last-child {
  align-self: stretch;
}
.ctx {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  height: 28px;
  margin-left: 4px;
  padding: 0 4px 0 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-2);
  background: var(--card);
  box-shadow: inset 0 0 0 1px rgba(18, 181, 160, 0.25), var(--shadow-sm);
  pointer-events: auto;
}
.ctx span {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ctx svg:first-child {
  flex: none;
  color: var(--accent);
}
.ctx b {
  font-weight: 600;
  color: var(--accent-strong);
}
.ctx-x {
  flex: none;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--text-3);
}
.ctx-x:hover {
  background: var(--hover);
  color: var(--text-1);
}
.tool {
  flex: none;
  width: 36px;
  height: 36px;
}
</style>
