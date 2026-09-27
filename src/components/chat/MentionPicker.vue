<script setup>
import { nextTick, ref, watch } from 'vue'
import Avatar from '@/components/common/Avatar.vue'

// 候选人列表；键盘上下选择由父组件（输入框）驱动，这里只负责显示和点选
const props = defineProps({
  people: { type: Array, required: true },
  active: { type: Number, default: 0 },
})
const emit = defineEmits(['pick', 'hover'])

const list = ref(null)
// 键盘移动时把选中项滚进可视区域
watch(
  () => props.active,
  () => nextTick(() => list.value?.querySelector('.on')?.scrollIntoView({ block: 'nearest' })),
)
</script>

<template>
  <div class="mention-picker" role="listbox" aria-label="选择要提及的人">
    <div class="title">选择要 @ 的人 <span>↑↓ 选择 · 回车确认 · Esc 取消</span></div>
    <div ref="list" class="list">
      <button
        v-for="(p, i) in people"
        :key="p.id"
        type="button"
        class="person"
        :class="{ on: i === active }"
        role="option"
        :aria-selected="i === active"
        @mousedown.prevent
        @mouseenter="emit('hover', i)"
        @click="emit('pick', p)"
      >
        <Avatar :user="p.id" :size="26" />
        <span class="name">{{ p.name }}</span>
        <span class="meta">{{ p.dept }} · {{ p.role }}</span>
      </button>
      <div v-if="!people.length" class="empty">没有匹配的成员</div>
    </div>
  </div>
</template>

<style scoped>
.mention-picker {
  width: 280px;
  max-width: calc(100vw - 32px);
  padding: 6px;
  border-radius: 16px;
  background: var(--popover);
  box-shadow: var(--shadow-pop), 0 0 0 0.5px var(--line-strong);
}
.title {
  display: flex;
  justify-content: space-between;
  padding: 4px 8px 6px;
  font-size: 12px;
  color: var(--text-2);
}
.title span {
  color: var(--text-3);
}
.list {
  max-height: 232px;
  overflow-y: auto;
}
.person {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 8px;
  border-radius: 10px;
  text-align: left;
}
/* 当前选中（键盘上下或鼠标移入）的人 */
.person.on {
  background: var(--list-active);
}
.name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-1);
}
.meta {
  margin-left: auto;
  overflow: hidden;
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.empty {
  padding: 12px 8px;
  font-size: 13px;
  color: var(--text-3);
}
</style>
