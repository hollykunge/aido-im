import { createRouter, createWebHashHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import TodoView from '@/views/TodoView.vue'
import MemoryView from '@/views/MemoryView.vue'
import SearchView from '@/views/SearchView.vue'

export const tabs = [
  { name: 'chat', label: '消息', path: '/chat' },
  { name: 'todo', label: 'TODO', path: '/todo' },
  { name: 'search', label: '搜索', path: '/search' },
]

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/home', redirect: '/chat' },
    { path: '/chat/:id?', name: 'chat', component: ChatView },
    { path: '/todo', name: 'todo', component: TodoView },
    { path: '/tasks', redirect: '/todo' },
    { path: '/memory', name: 'memory', component: MemoryView },
    { path: '/search', name: 'search', component: SearchView },
  ],
})
