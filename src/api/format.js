// 把后端的 ISO 时间、字节数换成界面上的说法

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const pad = (n) => String(n).padStart(2, '0')
const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate())

function dayDiff(d, now = new Date()) {
  return Math.round((startOfDay(d) - startOfDay(now)) / 86400000)
}

export const hm = (iso) => {
  const d = new Date(iso)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// 日期的说法：今天 / 昨天 / 一周内的周几 / 更早的日期
export function dayLabel(iso) {
  const d = new Date(iso)
  const diff = dayDiff(d)
  if (diff === 0) return '今天'
  if (diff === -1) return '昨天'
  if (diff < 0 && diff > -7) return WEEKDAYS[d.getDay()]
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

// 会话列表：今天显示时间，其余显示日期的说法
export function listTime(iso) {
  if (!iso) return ''
  return dayDiff(new Date(iso)) === 0 ? hm(iso) : dayLabel(iso)
}

// 分隔线：今天 08:30、昨天 17:20
export const stamp = (iso) => `${dayLabel(iso)} ${hm(iso)}`

// 消息气泡：今天只显示时间，其余带上日期说法
export function msgTime(iso) {
  const day = listTime(iso)
  return new Date(iso).toDateString() === new Date().toDateString() ? day : `${day} ${hm(iso)}`
}

export function fileSize(bytes) {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

// 记忆条目：9月18日
export function shortDate(iso) {
  const d = new Date(iso)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}
