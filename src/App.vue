<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { LoaderCircle, CloudOff, RefreshCw } from 'lucide-vue-next'
import AppShell from '@/layouts/AppShell.vue'
import LoginView from '@/views/LoginView.vue'
import { UNAUTHORIZED_EVENT } from '@/api'
import { useWorkspace } from '@/stores/workspace'
import { useAgent } from '@/stores/agent'
import { useRealtime } from '@/stores/realtime'

const ws = useWorkspace()
const agent = useAgent()
const realtime = useRealtime()

// 启动时从后端加载基础数据，加载完才渲染主界面；没登录（401）就先显示登录页
const state = ref('loading') // loading | login | ready | error
const error = ref('')
async function boot() {
  state.value = 'loading'
  try {
    await Promise.all([ws.init(), agent.init()])
    state.value = 'ready'
    // 数据加载完再订阅推送；连上时会再拉一次，补上中间漏掉的改动
    realtime.start()
  } catch (e) {
    if (e.status === 401) {
      state.value = 'login'
      return
    }
    error.value = e.message
    state.value = 'error'
  }
}
onMounted(boot)

// 用着用着会话没了（过期、在别处退出）：整页重新加载，清掉上一位用户的数据，回到登录页
function onUnauthorized() {
  if (state.value === 'ready') location.reload()
}
window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
onBeforeUnmount(() => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized))
</script>

<template>
  <AppShell v-if="state === 'ready'" />
  <LoginView v-else-if="state === 'login'" @success="boot" />
  <div v-else class="boot">
    <template v-if="state === 'loading'">
      <LoaderCircle :size="22" class="spin" />
      <span>正在连接服务…</span>
    </template>
    <template v-else>
      <CloudOff :size="28" />
      <b>暂时无法加载</b>
      <span class="muted">{{ error }}</span>
      <button class="btn btn-primary" @click="boot"><RefreshCw :size="14" /> 重试</button>
    </template>
  </div>
</template>

<style scoped>
.boot {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 16px;
  text-align: center;
  color: var(--text-2);
  font-size: 14px;
}
.boot b {
  color: var(--text-1);
  font-size: 16px;
}
.boot .btn {
  margin-top: 6px;
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
