// 后端请求：开发时经 Vite 代理转发到 Spring Boot（见 vite.config.js）
export const BASE = import.meta.env.VITE_API_BASE || '/api'

// 每个标签页一个 id：随请求带给后端，推送时跳过发起改动的这个标签页（它已从响应里拿到结果）
export const CLIENT_ID = crypto.randomUUID()

export class ApiError extends Error {
  constructor(status, detail) {
    super(detail || `请求失败（${status}）`)
    this.status = status
  }
}

// 登录会话失效（过期、在别处退出）时触发，App 据此回到登录页
export const UNAUTHORIZED_EVENT = 'aido:unauthorized'

// CSRF：后端把令牌放在可读的 XSRF-TOKEN Cookie 里，写请求原样放进 X-XSRF-TOKEN 头
function csrfToken() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : null
}

async function request(method, path, body) {
  const headers = { 'X-Client-Id': CLIENT_ID }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    const token = csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  let res
  try {
    // 同源请求默认带会话 Cookie
    res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    throw new ApiError(0, '连不上服务，请检查后端是否已启动')
  }
  if (res.status === 204) return null
  const data = res.headers.get('content-type')?.includes('json') ? await res.json() : null
  // 已登录状态下收到 401 说明会话没了；登录接口自己的 401 是「账号或密码错误」，不算
  if (res.status === 401 && path !== '/auth/login') window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  // 错误按 RFC 9457 Problem Details 返回，detail 可直接展示
  if (!res.ok) throw new ApiError(res.status, data?.detail)
  return data
}

/**
 * 上传文件（multipart）。用 XHR 而不是 fetch：fetch 拿不到上传进度。
 * 约定与 request 一致：带标签页 id 和 CSRF 头，错误按 Problem Details 抛 ApiError，401 通知 App。
 * @param onProgress (0–1) => void
 * @param signal AbortSignal，用于取消
 */
function upload(path, file, { onProgress, signal } = {}) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', BASE + path)
    xhr.setRequestHeader('X-Client-Id', CLIENT_ID)
    const token = csrfToken()
    if (token) xhr.setRequestHeader('X-XSRF-TOKEN', token)
    xhr.responseType = 'json'
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.(e.loaded / e.total)
    xhr.onload = () => {
      if (xhr.status === 401) window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
      if (xhr.status >= 200 && xhr.status < 300) resolve(xhr.response)
      else reject(new ApiError(xhr.status, xhr.response?.detail))
    }
    xhr.onerror = () => reject(new ApiError(0, '网络中断，上传失败'))
    xhr.onabort = () => reject(new ApiError(-1, '已取消'))
    signal?.addEventListener('abort', () => xhr.abort())
    const form = new FormData()
    form.append('file', file)
    xhr.send(form)
  })
}

export const http = {
  upload,
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body ?? {}),
  patch: (path, body) => request('PATCH', path, body),
  del: (path) => request('DELETE', path),
}

export const qs = (params) => {
  const p = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => v !== undefined && v !== null && v !== '' && p.set(k, v))
  const s = p.toString()
  return s ? `?${s}` : ''
}
