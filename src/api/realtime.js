// 实时推送连接：一次性票据握手、心跳、断线重连。只负责连接本身，事件交给调用方处理
import { ref } from 'vue'
import { BASE, CLIENT_ID } from './http'
import { api } from './index'

// 这么久没收到服务端任何消息（包括 25 秒一次的 ping）就认为连接已死，主动断开重连
const SILENCE_MS = 60_000
const RETRY_MIN = 1_000
const RETRY_MAX = 30_000
// 服务端因同一用户连接过多挤掉了这条，不自动重连，等标签页回到前台再说
const TOO_MANY = 4009
// 登录会话已结束（退出或过期）：不重连，交给调用方回到登录页
const SESSION_ENDED = 4401

/**
 * @param onEvent  (type, data) => void 业务事件
 * @param onResync () => void 每次连上（含首次）后调用：由调用方重新拉取数据，补上没推到的改动
 * @param onSessionEnded () => void 服务端因登录会话结束而断开
 */
export function createRealtime({ onEvent, onResync, onSessionEnded, status = ref('connecting') }) {
  // status：connecting 连接中 | open 在线 | offline 离线
  let socket = null
  let connecting = false
  let attempt = 0
  let retryTimer
  let silenceTimer
  let stopped = false

  async function connect() {
    if (socket || connecting || stopped) return
    connecting = true
    clearTimeout(retryTimer)
    status.value = 'connecting'
    let ticket
    try {
      ;({ ticket } = await api.realtimeTicket())
    } catch (e) {
      connecting = false
      // 拿票据时 401：会话没了，http 层已经通知了 App，不再重试
      if (e.status === 401) {
        stopped = true
        return
      }
      return retry()
    }
    const origin = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}`
    const ws = new WebSocket(`${origin}${BASE}/realtime?ticket=${encodeURIComponent(ticket)}&client=${CLIENT_ID}`)
    socket = ws
    connecting = false

    ws.onmessage = (e) => {
      watchSilence(ws)
      const { type, data } = JSON.parse(e.data)
      if (type === 'ping') return ws.send('{"type":"pong"}')
      if (type === 'hello') {
        status.value = 'open'
        attempt = 0
        // 从页面加载数据到订阅生效之间、以及断线期间的改动都没推到，每次连上都重新拉一次
        onResync()
        return
      }
      onEvent(type, data)
    }
    ws.onclose = (e) => {
      clearTimeout(silenceTimer)
      if (socket === ws) socket = null
      status.value = 'offline'
      if (e.code === SESSION_ENDED) {
        stopped = true
        return onSessionEnded?.()
      }
      if (e.code !== TOO_MANY) retry()
    }
    // 出错后一定会触发 onclose，统一在那里处理
    ws.onerror = () => {}
  }

  // 指数退避加随机抖动，避免服务重启后所有客户端同时涌上来
  function retry() {
    const delay = Math.min(RETRY_MAX, RETRY_MIN * 2 ** attempt) * (0.5 + Math.random() / 2)
    attempt++
    clearTimeout(retryTimer)
    retryTimer = setTimeout(connect, delay)
  }

  function watchSilence(ws) {
    clearTimeout(silenceTimer)
    silenceTimer = setTimeout(() => ws.close(4000, 'silence'), SILENCE_MS)
  }

  // 网络恢复、标签页回到前台时不等退避，立即重连
  function reconnectNow() {
    if (socket || connecting || stopped) return
    attempt = 0
    connect()
  }
  window.addEventListener('online', reconnectNow)
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && reconnectNow())

  connect()
  return { status }
}
