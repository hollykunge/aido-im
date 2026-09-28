<script setup>
import { ref } from 'vue'
import { LoaderCircle, Eye, EyeOff } from 'lucide-vue-next'
import { api } from '@/api'

const emit = defineEmits(['success'])

const login = ref('')
const password = ref('')
const showPassword = ref(false)
const error = ref('')
const submitting = ref(false)
// 本地开发时提示演示账号在哪
const isDev = import.meta.env.DEV

async function submit() {
  if (submitting.value) return
  if (!login.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    await api.login(login.value.trim(), password.value)
    emit('success')
  } catch (e) {
    error.value = e.message
    password.value = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <form class="card" novalidate @submit.prevent="submit">
      <img class="logo" src="/favicon.svg" alt="" />
      <h1>登录 AIDo</h1>
      <p class="sub">用公司账号登录</p>

      <label class="field">
        <span>账号</span>
        <input
          v-model="login"
          name="username"
          autocomplete="username"
          autocapitalize="off"
          spellcheck="false"
          autofocus
          :disabled="submitting"
        />
      </label>
      <label class="field">
        <span>密码</span>
        <span class="pw">
          <input
            v-model="password"
            name="password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            :disabled="submitting"
          />
          <button
            type="button"
            class="eye"
            :title="showPassword ? '隐藏密码' : '显示密码'"
            :aria-label="showPassword ? '隐藏密码' : '显示密码'"
            @click="showPassword = !showPassword"
          >
            <EyeOff v-if="showPassword" :size="16" />
            <Eye v-else :size="16" />
          </button>
        </span>
      </label>

      <p class="error" role="alert">{{ error }}</p>

      <button class="btn btn-primary submit" type="submit" :disabled="submitting">
        <LoaderCircle v-if="submitting" :size="16" class="spin" />
        {{ submitting ? '登录中…' : '登录' }}
      </button>
      <p v-if="isDev" class="hint">演示账号见 server/README.md</p>
    </form>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: grid;
  place-items: center;
  padding: 16px;
}
.card {
  width: 100%;
  max-width: 360px;
  display: flex;
  flex-direction: column;
  padding: 32px 28px 28px;
  border-radius: var(--r-xl);
  background: var(--window);
  box-shadow: var(--shadow-window);
}
.logo {
  width: 40px;
  height: 40px;
  margin-bottom: 16px;
}
h1 {
  margin: 0;
  font-size: 22px;
}
.sub {
  margin: 4px 0 22px;
  font-size: 13px;
  color: var(--text-2);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
}
.field input {
  width: 100%;
  height: 40px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--r-sm);
  outline: none;
  background: var(--card-sub);
  box-shadow: inset 0 0 0 1px var(--line-strong);
  color: var(--text-1);
  font-size: 14px;
  font-weight: 400;
  transition: box-shadow 0.15s;
}
.field input:focus {
  box-shadow: inset 0 0 0 1.5px var(--accent);
}
.pw {
  position: relative;
  display: block;
}
.pw input {
  padding-right: 40px;
}
.eye {
  position: absolute;
  top: 50%;
  right: 6px;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: var(--r-xs);
  color: var(--text-3);
  transform: translateY(-50%);
}
.eye:hover {
  color: var(--text-1);
  background: var(--hover);
}
.error {
  min-height: 18px;
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--danger);
}
.submit {
  justify-content: center;
  height: 40px;
  font-size: 14px;
}
.hint {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--text-3);
  text-align: center;
}
.submit:disabled {
  opacity: 0.7;
  cursor: default;
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
