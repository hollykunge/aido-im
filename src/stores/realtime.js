import { defineStore } from 'pinia'
import { ref } from 'vue'
import { createRealtime } from '@/api/realtime'
import { toCapability, toFeedItem, toMemory, toMsg, toTodo, UNAUTHORIZED_EVENT } from '@/api'
import { useWorkspace } from './workspace'
import { useAgent } from './agent'
import { useMemory } from './memory'

// 实时推送：把服务端事件落到各个 store。事件定义见后端 RealtimeDispatcher
export const useRealtime = defineStore('realtime', () => {
  const status = ref('connecting')
  let started = false

  function start() {
    if (started) return
    started = true
    const ws = useWorkspace()
    const agent = useAgent()
    const memory = useMemory()
    const handlers = {
      'message.created': ({ message, unread }) => ws.receiveMessage(toMsg(message), unread),
      'conversation.read': ({ conversationId }) => ws.readElsewhere(conversationId),
      'todos.changed': ({ todos }) => ws.applyTodos(todos.map(toTodo)),
      'agent.items': ({ items }) => agent.receiveItems(items.map(toFeedItem)),
      'user.updated': ({ user }) => ws.applyUser(user),
      // 记忆、偏好、能力开关推的都是完整快照，直接替换
      'memory.changed': (m) => memory.receive(toMemory(m)),
      'preferences.changed': (prefs) => {
        agent.applyPreferences(prefs)
        memory.learning = prefs.memoryLearning
      },
      'capabilities.changed': ({ capabilities }) => (agent.capabilities = capabilities.map(toCapability)),
    }
    createRealtime({
      status,
      onEvent: (type, data) => handlers[type]?.(data),
      onResync: () => Promise.all([ws.resync(), agent.reloadFeed(), agent.reloadSettings(), memory.reload()]),
      onSessionEnded: () => window.dispatchEvent(new Event(UNAUTHORIZED_EVENT)),
    })
  }

  return { status, start }
})
